import { describe, expect, it, vi } from 'vitest';
import { ApiError, errorMessage, parseProblemResponse, problemToError } from './problem';
import { applyApiErrors } from '../forms';

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

describe('Problem Details parsing', () => {
  it('parses a full RFC 9457 body including code, traceId and field errors', async () => {
    const res = new Response(
      JSON.stringify({
        type: 'about:blank',
        title: 'Conflict',
        status: 409,
        detail: 'The selected time slot is no longer available.',
        instance: '/api/v1/bookings',
        code: 'SLOT_UNAVAILABLE',
        traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
        errors: [{ field: 'startTime', message: 'must be in the future' }],
      }),
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    );
    const err = await parseProblemResponse(res);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
    expect(err.code).toBe('SLOT_UNAVAILABLE');
    expect(err.title).toBe('Conflict');
    expect(err.detail).toBe('The selected time slot is no longer available.');
    expect(err.message).toBe(err.detail);
    expect(err.traceId).toBe('4bf92f3577b34da6a3ce929d0e0e4736');
    expect(err.fieldErrors).toEqual([{ field: 'startTime', message: 'must be in the future' }]);
  });

  it('falls back to a status-derived code/title when the body is not JSON', async () => {
    const res = new Response('<html>Bad gateway</html>', { status: 502 });
    const err = await parseProblemResponse(res);
    expect(err.code).toBe('SERVER_ERROR');
    expect(err.title).toBe('Bad Gateway');
    expect(err.detail).toMatch(/server/i);
  });

  it('handles empty bodies and maps known statuses', async () => {
    const err = await parseProblemResponse(new Response(null, { status: 404 }));
    expect(err.code).toBe('NOT_FOUND');
    expect(err.detail).toBe('Not Found');
  });

  it('reads Retry-After for 429 and includes it in the message', async () => {
    const res = new Response(JSON.stringify({ code: 'RATE_LIMITED', detail: 'Too many requests.' }), { status: 429, headers: { 'Retry-After': '30' } });
    const err = await parseProblemResponse(res);
    expect(err.retryAfterSeconds).toBe(30);
    expect(errorMessage(err)).toBe('Too many requests. (retry in 30s)');
  });

  it('ignores malformed errors[] entries', () => {
    const err = problemToError(400, { code: 'VALIDATION_FAILED', detail: 'Invalid', errors: [null, 'x', { field: 'title' }] });
    expect(err.fieldErrors).toEqual([{ field: 'title', message: 'Invalid value' }]);
  });

  it('maps field errors into a form and surfaces the rest at root', () => {
    const err = problemToError(400, {
      code: 'VALIDATION_FAILED',
      detail: 'Validation failed',
      errors: [
        { field: 'title', message: 'must not be blank' },
        { field: 'inviteeEmails[2]', message: 'must be an email' },
        { field: 'startTime', message: 'must be in the future' },
        { field: 'unknownField', message: 'nope' },
      ],
    });
    const setError = vi.fn();
    const mapped = applyApiErrors(err, setError, ['title', 'inviteeEmails', 'date'], { toast: false, aliases: { startTime: 'date' } });
    expect(mapped).toEqual(['title', 'inviteeEmails', 'date']);
    expect(setError).toHaveBeenCalledWith('title', { type: 'server', message: 'must not be blank' });
    expect(setError).toHaveBeenCalledWith('inviteeEmails', { type: 'server', message: 'must be an email' });
    expect(setError).toHaveBeenCalledWith('date', { type: 'server', message: 'must be in the future' });
    expect(setError).toHaveBeenCalledWith('root.server', { type: 'server', message: 'Validation failed — unknownField: nope' });
  });
});
