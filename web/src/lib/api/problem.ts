/** RFC 9457 Problem Details as emitted by the backend (see docs/04-api-contracts.md). */
export interface FieldError {
  field: string;
  message: string;
}

export interface ProblemDetails {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  traceId?: string;
  errors?: FieldError[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly title: string;
  readonly detail: string;
  readonly traceId?: string;
  readonly fieldErrors: FieldError[];
  readonly retryAfterSeconds?: number;

  constructor(init: {
    status: number;
    code: string;
    title: string;
    detail: string;
    traceId?: string;
    fieldErrors?: FieldError[];
    retryAfterSeconds?: number;
  }) {
    super(init.detail || init.title || init.code);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.title = init.title;
    this.detail = init.detail;
    this.traceId = init.traceId;
    this.fieldErrors = init.fieldErrors ?? [];
    this.retryAfterSeconds = init.retryAfterSeconds;
  }
}

const STATUS_FALLBACK_CODES: Record<number, string> = {
  400: 'MALFORMED_REQUEST',
  401: 'UNAUTHENTICATED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  409: 'CONFLICT',
  413: 'FILE_TOO_LARGE',
  415: 'FILE_TYPE_NOT_ALLOWED',
  429: 'RATE_LIMITED',
};

const STATUS_TITLES: Record<number, string> = {
  400: 'Bad Request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  409: 'Conflict',
  413: 'Payload Too Large',
  415: 'Unsupported Media Type',
  422: 'Unprocessable Content',
  429: 'Too Many Requests',
  500: 'Internal Server Error',
  502: 'Bad Gateway',
  503: 'Service Unavailable',
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseFieldErrors(raw: unknown): FieldError[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter(isRecord).map((e) => ({
    field: typeof e.field === 'string' ? e.field : '',
    message: typeof e.message === 'string' ? e.message : 'Invalid value',
  }));
}

/** Builds an ApiError from an already-decoded body (any shape) and HTTP status. */
export function problemToError(status: number, body: unknown, retryAfter?: string | null): ApiError {
  const p = isRecord(body) ? body : {};
  const title = typeof p.title === 'string' && p.title ? p.title : (STATUS_TITLES[status] ?? 'Request failed');
  const code =
    typeof p.code === 'string' && p.code
      ? p.code
      : (STATUS_FALLBACK_CODES[status] ?? (status >= 500 ? 'SERVER_ERROR' : 'UNKNOWN_ERROR'));
  const detail =
    typeof p.detail === 'string' && p.detail
      ? p.detail
      : status >= 500
        ? 'The server encountered an error. Please try again.'
        : title;
  const retry = retryAfter ? Number.parseInt(retryAfter, 10) : undefined;
  return new ApiError({
    status,
    code,
    title,
    detail,
    traceId: typeof p.traceId === 'string' ? p.traceId : undefined,
    fieldErrors: parseFieldErrors(p.errors),
    retryAfterSeconds: retry !== undefined && Number.isFinite(retry) ? retry : undefined,
  });
}

/** Parses a non-OK fetch Response into an ApiError. Never throws. */
export async function parseProblemResponse(res: Response): Promise<ApiError> {
  let body: unknown = undefined;
  try {
    const text = await res.text();
    if (text) {
      try {
        body = JSON.parse(text);
      } catch {
        body = undefined;
      }
    }
  } catch {
    body = undefined;
  }
  return problemToError(res.status, body, res.headers.get('Retry-After'));
}

export function networkError(cause: unknown): ApiError {
  const err = new ApiError({
    status: 0,
    code: 'NETWORK_ERROR',
    title: 'Network error',
    detail: 'Could not reach the server. Check your connection and try again.',
  });
  (err as { cause?: unknown }).cause = cause;
  return err;
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

/** Human-readable message for any thrown value. */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === 'RATE_LIMITED' && e.retryAfterSeconds) {
      return `${e.detail} (retry in ${e.retryAfterSeconds}s)`;
    }
    return e.detail;
  }
  if (e instanceof Error) return e.message;
  return 'Something went wrong.';
}
