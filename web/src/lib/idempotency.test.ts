import { describe, expect, it, vi } from 'vitest';
import { ApiError } from './api/problem';
import { IdempotencyKeys, withIdempotencyRetry } from './idempotency';

const inProgress = () => new ApiError({ status: 409, code: 'IDEMPOTENCY_IN_PROGRESS', title: 'Conflict', detail: 'In progress' });

describe('IdempotencyKeys (W-12)', () => {
  it('reuses the key only for the identical payload and rotates it when the payload changes', () => {
    const keys = new IdempotencyKeys();
    const a = keys.keyFor({ slot: '10:00', name: 'Ana' });
    expect(keys.keyFor({ slot: '10:00', name: 'Ana' })).toBe(a); // retry after a lost response
    const b = keys.keyFor({ slot: '11:00', name: 'Ana' }); // picked another slot
    expect(b).not.toBe(a);
    const c = keys.keyFor({ slot: '11:00', name: 'Ana B.' }); // edited details
    expect(c).not.toBe(b);
    expect(keys.keyFor({ slot: '11:00', name: 'Ana B.' })).toBe(c);
  });

  it('reset() starts a new request even for the same payload', () => {
    const keys = new IdempotencyKeys();
    const a = keys.keyFor({ x: 1 });
    keys.reset();
    expect(keys.keyFor({ x: 1 })).not.toBe(a);
  });
});

describe('withIdempotencyRetry (IDEMPOTENCY_IN_PROGRESS)', () => {
  it('waits and retries once with the same request when the first attempt is still processing', async () => {
    const sleep = vi.fn(async () => undefined);
    const send = vi.fn().mockRejectedValueOnce(inProgress()).mockResolvedValueOnce({ id: 'b1' });
    await expect(withIdempotencyRetry(send, { sleep, delayMs: 10 })).resolves.toEqual({ id: 'b1' });
    expect(send).toHaveBeenCalledTimes(2);
    expect(sleep).toHaveBeenCalledWith(10);
  });

  it('honours Retry-After for the pause', async () => {
    const sleep = vi.fn(async () => undefined);
    const err = new ApiError({ status: 409, code: 'IDEMPOTENCY_IN_PROGRESS', title: 'Conflict', detail: '', retryAfterSeconds: 2 });
    const send = vi.fn().mockRejectedValueOnce(err).mockResolvedValueOnce('ok');
    await withIdempotencyRetry(send, { sleep });
    expect(sleep).toHaveBeenCalledWith(2000);
  });

  it('reports a clear "still processing" message when it is still in progress after the retry', async () => {
    const send = vi.fn().mockRejectedValue(inProgress());
    await expect(withIdempotencyRetry(send, { sleep: async () => undefined })).rejects.toMatchObject({
      code: 'IDEMPOTENCY_IN_PROGRESS',
      detail: expect.stringMatching(/still being processed/i),
    });
    expect(send).toHaveBeenCalledTimes(2);
  });

  it('does not retry other errors', async () => {
    const err = new ApiError({ status: 409, code: 'SLOT_UNAVAILABLE', title: 'Conflict', detail: 'Taken' });
    const send = vi.fn().mockRejectedValue(err);
    await expect(withIdempotencyRetry(send, { sleep: async () => undefined })).rejects.toBe(err);
    expect(send).toHaveBeenCalledTimes(1);
  });
});
