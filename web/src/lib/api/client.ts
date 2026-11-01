import { ApiError, networkError, parseProblemResponse } from './problem';
import type { AuthResponse } from './types';

/**
 * Low-level HTTP client for the backend REST API.
 *
 * - Access token lives in memory only (never in localStorage / sessionStorage).
 * - On a 401 for an authenticated request, POST /auth/refresh is called once (cookie based,
 *   `credentials: 'include'`, `X-Requested-With: XMLHttpRequest`) and the original request retried.
 *   Concurrent 401s share one refresh call.
 * - If the refresh is rejected (401/403), the token is cleared and the auth-failure handler (redirect to /login) runs.
 *   A transient refresh failure (429, 5xx, network) keeps the session and surfaces as a retryable ApiError.
 */

export type AuthMode =
  /** Bearer token required; 401 triggers refresh + retry, then redirect to /login. */
  | 'required'
  /** Bearer token sent if present (e.g. guest-capable endpoints); 401 refreshes only if a token was sent; never redirects. */
  | 'optional'
  /** Public endpoint; no Authorization header, no refresh. */
  | 'none';

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined | null>;
  headers?: Record<string, string>;
  auth?: AuthMode;
  signal?: AbortSignal;
}

type AuthListener = (auth: AuthResponse | null) => void;

/** 401 codes that mean "your session/token is not valid" (as opposed to e.g. a wrong current password). */
const SESSION_401_CODES = new Set(['UNAUTHENTICATED', 'TOKEN_EXPIRED', 'TOKEN_INVALID']);

/**
 * True when a response means the access token is missing/expired, so refreshing may help.
 * A 401 with another code (e.g. INVALID_CREDENTIALS for a wrong current password) is a normal error.
 */
async function isSessionFailure(res: Response): Promise<boolean> {
  if (res.status !== 401) return false;
  try {
    const body = (await res.clone().json()) as { code?: unknown };
    return typeof body?.code !== 'string' || SESSION_401_CODES.has(body.code);
  } catch {
    return true;
  }
}

type RefreshOutcome =
  | { auth: AuthResponse }
  /** The server rejected the refresh token: the session is over. */
  | { auth: null; rejected: true }
  /** Could not refresh right now (rate limit, server/proxy error, offline): keep the session, try again later. */
  | { auth: null; rejected: false; error: ApiError };

const REFRESH_LOCK = 'mp-auth-refresh';

function isAuthRejection(r: AuthResponse | ApiError): boolean {
  return r instanceof ApiError && (r.status === 401 || r.status === 403);
}

/** Runs fn while holding a cross-tab lock when the Web Locks API exists; otherwise just runs it. */
function withRefreshLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
  if (!locks?.request) return fn();
  // fn never rejects here, so a rejection means the lock itself was unavailable (e.g. opaque origin).
  return locks.request(REFRESH_LOCK, fn).catch(() => fn()) as Promise<T>;
}

export interface ApiClientConfig {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
}

export class ApiClient {
  private accessToken: string | null = null;
  private refreshPromise: Promise<RefreshOutcome> | null = null;
  private authListeners = new Set<AuthListener>();
  private authFailureHandler: (() => void) | null = null;
  readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;

  constructor(config: ApiClientConfig = {}) {
    this.baseUrl = (config.baseUrl ?? '/api/v1').replace(/\/$/, '');
    this.fetchImpl = config.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  }

  getAccessToken(): string | null {
    return this.accessToken;
  }

  /** Stores the session from a login/refresh response and notifies listeners. */
  setSession(auth: AuthResponse | null): void {
    this.accessToken = auth?.accessToken ?? null;
    for (const l of this.authListeners) l(auth);
  }

  onAuthChange(listener: AuthListener): () => void {
    this.authListeners.add(listener);
    return () => this.authListeners.delete(listener);
  }

  setAuthFailureHandler(handler: (() => void) | null): void {
    this.authFailureHandler = handler;
  }

  buildUrl(path: string, query?: RequestOptions['query']): string {
    const url = `${this.baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
    if (!query) return url;
    const params = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) {
      if (v === undefined || v === null || v === '') continue;
      params.set(k, String(v));
    }
    const qs = params.toString();
    return qs ? `${url}?${qs}` : url;
  }

  /**
   * Calls POST /auth/refresh using the HttpOnly refresh cookie. Single-flight: concurrent callers share the result.
   * Resolves to null (never rejects) when the session cannot be refreshed.
   */
  refresh(): Promise<AuthResponse | null> {
    return this.refreshOutcome().then((o) => o.auth);
  }

  /**
   * Single-flight refresh that tells an auth rejection (401/403: the session is gone → cleared) apart from a
   * transient failure (429, 5xx, proxy errors, network: the session is kept and the error is reported).
   * Serialized across tabs with the Web Locks API where available: the backend rotates refresh tokens, so two
   * tabs refreshing with the same cookie at once would get the slower one a 401 (and signed out). Under the
   * lock the second tab sends the cookie the first tab's rotation just set. Without Web Locks (jsdom, very
   * old browsers) the call simply runs unserialized.
   */
  private refreshOutcome(): Promise<RefreshOutcome> {
    if (!this.refreshPromise) {
      this.refreshPromise = (async (): Promise<RefreshOutcome> => {
        try {
          const res = await withRefreshLock(() => this.postRefresh());
          if (isAuthRejection(res)) {
            this.setSession(null);
            return { auth: null, rejected: true };
          }
          if (res instanceof ApiError) return { auth: null, rejected: false, error: res };
          this.setSession(res);
          return { auth: res };
        } finally {
          this.refreshPromise = null;
        }
      })();
    }
    return this.refreshPromise;
  }

  /** One POST /auth/refresh: the new session, or the failure as an ApiError (never rejects). */
  private async postRefresh(): Promise<AuthResponse | ApiError> {
    try {
      const res = await this.fetchImpl(this.buildUrl('/auth/refresh'), {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          'X-Requested-With': 'XMLHttpRequest',
        },
        body: '{}',
      });
      if (!res.ok) return await parseProblemResponse(res);
      return (await res.json()) as AuthResponse;
    } catch (e) {
      return networkError(e);
    }
  }

  private async send(path: string, opts: RequestOptions, token: string | null): Promise<Response> {
    const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
    let body: BodyInit | undefined;
    if (opts.body instanceof FormData) {
      body = opts.body;
    } else if (opts.body instanceof Blob) {
      // Raw bytes (a recording chunk).
      headers['Content-Type'] = 'application/octet-stream';
      body = opts.body;
    } else if (opts.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opts.body);
    }
    if (token) headers.Authorization = `Bearer ${token}`;
    const isCookieAuthPath = path.startsWith('/auth/');
    if (isCookieAuthPath) headers['X-Requested-With'] = 'XMLHttpRequest';
    try {
      return await this.fetchImpl(this.buildUrl(path, opts.query), {
        method: opts.method ?? 'GET',
        headers,
        body,
        signal: opts.signal,
        credentials: isCookieAuthPath ? 'include' : 'same-origin',
      });
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') throw e;
      throw networkError(e);
    }
  }

  async request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
    const mode: AuthMode = opts.auth ?? 'required';
    const token = mode === 'none' ? null : this.accessToken;
    let res = await this.send(path, opts, token);

    if (mode !== 'none' && (mode === 'required' || token) && (await isSessionFailure(res))) {
      const refreshed = await this.refreshOutcome();
      if (refreshed.auth) {
        res = await this.send(path, opts, refreshed.auth.accessToken);
      } else if (!refreshed.rejected) {
        // Transient: the session may still be fine — don't sign out, let the caller retry.
        throw refreshed.error;
      } else if (mode === 'required') {
        this.authFailureHandler?.();
        throw await parseProblemResponse(res);
      }
    }

    if (!res.ok) {
      const err = await parseProblemResponse(res);
      if (mode === 'required' && err.status === 401 && SESSION_401_CODES.has(err.code)) this.authFailureHandler?.();
      throw err;
    }
    const text = await res.text();
    if (!text) return undefined as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new ApiError({
        status: res.status,
        code: 'MALFORMED_RESPONSE',
        title: 'Malformed response',
        detail: 'The server returned an unexpected response.',
      });
    }
  }

  get<T>(path: string, opts: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return this.request<T>(path, { ...opts, method: 'GET' });
  }
  post<T>(path: string, body?: unknown, opts: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return this.request<T>(path, { ...opts, method: 'POST', body });
  }
  put<T>(path: string, body?: unknown, opts: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return this.request<T>(path, { ...opts, method: 'PUT', body });
  }
  patch<T>(path: string, body?: unknown, opts: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return this.request<T>(path, { ...opts, method: 'PATCH', body });
  }
  delete<T = void>(path: string, opts: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
    return this.request<T>(path, { ...opts, method: 'DELETE' });
  }
}

export const api = new ApiClient({ baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1' });
