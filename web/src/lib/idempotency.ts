import { ApiError } from './api/problem';
import { newIdempotencyKey } from './utils';

/**
 * Idempotency-Key per request payload: retrying the identical payload (e.g. after a lost response) reuses the
 * key so the server can replay the first result; any change to the payload gets a new key, because the server
 * rejects a key reused with a different request (409).
 */
export class IdempotencyKeys {
  private key = newIdempotencyKey();
  private payload: string | null = null;

  keyFor(payload: unknown): string {
    const p = JSON.stringify(payload) ?? '';
    if (this.payload !== null && this.payload !== p) this.key = newIdempotencyKey();
    this.payload = p;
    return this.key;
  }

  /** After a definitive outcome (success, or e.g. "slot taken"): the next request is a new one. */
  reset(): void {
    this.key = newIdempotencyKey();
    this.payload = null;
  }
}

export const IDEMPOTENCY_IN_PROGRESS = 'IDEMPOTENCY_IN_PROGRESS';
const DEFAULT_IN_PROGRESS_DELAY_MS = 1500;
const MAX_IN_PROGRESS_DELAY_MS = 5000;

/**
 * Runs an idempotent POST; if the server answers 409 IDEMPOTENCY_IN_PROGRESS (the first attempt with this key
 * is still being processed) waits briefly and asks once more with the same key. A second "in progress" is
 * reported as a clear, retryable error instead of the raw conflict.
 */
export async function withIdempotencyRetry<T>(
  send: () => Promise<T>,
  opts: { delayMs?: number; sleep?: (ms: number) => Promise<void> } = {},
): Promise<T> {
  const sleep = opts.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  try {
    return await send();
  } catch (e) {
    if (!isInProgress(e)) throw e;
    const hinted = e.retryAfterSeconds !== undefined ? e.retryAfterSeconds * 1000 : undefined;
    await sleep(Math.min(hinted ?? opts.delayMs ?? DEFAULT_IN_PROGRESS_DELAY_MS, MAX_IN_PROGRESS_DELAY_MS));
    try {
      return await send();
    } catch (e2) {
      if (!isInProgress(e2)) throw e2;
      throw new ApiError({
        status: e2.status,
        code: e2.code,
        title: e2.title,
        detail: 'Your request is still being processed. Please wait a moment and try again.',
        traceId: e2.traceId,
        retryAfterSeconds: e2.retryAfterSeconds,
      });
    }
  }
}

function isInProgress(e: unknown): e is ApiError {
  return e instanceof ApiError && e.status === 409 && e.code === IDEMPOTENCY_IN_PROGRESS;
}
