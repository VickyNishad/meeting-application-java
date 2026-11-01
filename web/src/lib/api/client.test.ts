import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from './client';
import { ApiError } from './problem';
import type { AuthResponse } from './types';

const user = {
  id: 'u1',
  displayName: 'Ada',
  email: 'ada@example.com',
  timeZone: 'UTC',
  emailVerified: true,
  systemRole: 'USER' as const,
  status: 'ACTIVE' as const,
  createdAt: '2026-01-01T00:00:00Z',
};

const auth = (token: string): AuthResponse => ({ accessToken: token, tokenType: 'Bearer', expiresIn: 900, user });

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

type Call = { url: string; init: RequestInit };

function setup(handler: (call: Call, n: number) => Response | Promise<Response>) {
  const calls: Call[] = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call = { url: String(input), init: init ?? {} };
    calls.push(call);
    return handler(call, calls.length);
  });
  const client = new ApiClient({ baseUrl: '/api/v1', fetchImpl: fetchImpl as unknown as typeof fetch });
  return { client, calls, fetchImpl };
}

const header = (c: Call, name: string) => (c.init.headers as Record<string, string> | undefined)?.[name];

describe('ApiClient', () => {
  it('sends the in-memory bearer token and never touches localStorage', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const { client, calls } = setup(() => json(200, { ok: true }));
    client.setSession(auth('tok-1'));
    await expect(client.get('/users/me')).resolves.toEqual({ ok: true });
    expect(header(calls[0]!, 'Authorization')).toBe('Bearer tok-1');
    expect(calls[0]!.url).toBe('/api/v1/users/me');
    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });

  it('on 401 refreshes once (cookie + X-Requested-With) and retries with the new token', async () => {
    const { client, calls } = setup((c) => {
      if (c.url.endsWith('/auth/refresh')) return json(200, auth('tok-2'));
      return header(c, 'Authorization') === 'Bearer tok-2' ? json(200, { id: 'm1' }) : json(401, { code: 'TOKEN_EXPIRED' });
    });
    client.setSession(auth('tok-1'));
    const res = await client.get<{ id: string }>('/meetings/m1');
    expect(res).toEqual({ id: 'm1' });
    expect(calls.map((c) => c.url)).toEqual(['/api/v1/meetings/m1', '/api/v1/auth/refresh', '/api/v1/meetings/m1']);
    const refresh = calls[1]!;
    expect(refresh.init.method).toBe('POST');
    expect(refresh.init.credentials).toBe('include');
    expect(header(refresh, 'X-Requested-With')).toBe('XMLHttpRequest');
    expect(client.getAccessToken()).toBe('tok-2');
  });

  it('shares a single refresh between concurrent 401s', async () => {
    let refreshes = 0;
    const { client } = setup(async (c) => {
      if (c.url.endsWith('/auth/refresh')) {
        refreshes++;
        await new Promise((r) => setTimeout(r, 10));
        return json(200, auth('fresh'));
      }
      return header(c, 'Authorization') === 'Bearer fresh' ? json(200, { url: c.url }) : json(401, {});
    });
    client.setSession(auth('stale'));
    const results = await Promise.all([client.get('/a'), client.get('/b'), client.get('/c')]);
    expect(results).toHaveLength(3);
    expect(refreshes).toBe(1);
  });

  it('when refresh fails: clears the session, calls the auth-failure handler (→ /login) and throws', async () => {
    const { client, calls } = setup((c) => (c.url.endsWith('/auth/refresh') ? json(401, { code: 'TOKEN_INVALID' }) : json(401, { code: 'UNAUTHENTICATED', detail: 'Sign in' })));
    const onFail = vi.fn();
    const onAuth = vi.fn();
    client.setAuthFailureHandler(onFail);
    client.setSession(auth('tok-1'));
    client.onAuthChange(onAuth);
    await expect(client.get('/users/me')).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED' });
    expect(onFail).toHaveBeenCalledTimes(1);
    expect(onAuth).toHaveBeenCalledWith(null);
    expect(client.getAccessToken()).toBeNull();
    // only one retry cycle: original + refresh
    expect(calls).toHaveLength(2);
  });

  it('does not loop when the retried request is still 401', async () => {
    const { client, calls } = setup((c) => (c.url.endsWith('/auth/refresh') ? json(200, auth('tok-2')) : json(401, {})));
    const onFail = vi.fn();
    client.setAuthFailureHandler(onFail);
    client.setSession(auth('tok-1'));
    await expect(client.get('/x')).rejects.toBeInstanceOf(ApiError);
    expect(calls).toHaveLength(3);
    expect(onFail).toHaveBeenCalledTimes(1);
  });

  it('a 401 that is not a session failure (wrong current password) neither refreshes nor redirects', async () => {
    const { client, calls } = setup(() => json(401, { code: 'INVALID_CREDENTIALS', detail: 'Current password is incorrect.' }));
    const onFail = vi.fn();
    client.setAuthFailureHandler(onFail);
    client.setSession(auth('tok-1'));
    await expect(client.post('/users/me/password', {})).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' });
    expect(calls).toHaveLength(1);
    expect(onFail).not.toHaveBeenCalled();
    expect(client.getAccessToken()).toBe('tok-1');
  });

  it('public endpoints (auth: none) never refresh or redirect', async () => {
    const { client, calls } = setup(() => json(401, { code: 'INVALID_CREDENTIALS', detail: 'Wrong email or password' }));
    const onFail = vi.fn();
    client.setAuthFailureHandler(onFail);
    await expect(client.post('/auth/login', { email: 'a', password: 'b' }, { auth: 'none' })).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS',
      detail: 'Wrong email or password',
    });
    expect(calls).toHaveLength(1);
    expect(onFail).not.toHaveBeenCalled();
  });

  it('optional-auth calls without a token (guests) do not attempt refresh', async () => {
    const { client, calls } = setup(() => json(401, { code: 'UNAUTHENTICATED' }));
    const onFail = vi.fn();
    client.setAuthFailureHandler(onFail);
    await expect(client.get('/meetings/m/chat', { auth: 'optional', headers: { 'X-Participant-Token': 'w' } })).rejects.toBeInstanceOf(ApiError);
    expect(calls).toHaveLength(1);
    expect(header(calls[0]!, 'X-Participant-Token')).toBe('w');
    expect(header(calls[0]!, 'Authorization')).toBeUndefined();
    expect(onFail).not.toHaveBeenCalled();
  });

  it('handles 204 No Content and builds query strings, skipping empty values', async () => {
    const { client, calls } = setup(() => new Response(null, { status: 204 }));
    client.setSession(auth('t'));
    await expect(client.post('/meetings/1/cancel', {})).resolves.toBeUndefined();
    await client.get('/meetings', { query: { scope: 'upcoming', page: 0, q: '', x: undefined } });
    expect(calls[1]!.url).toBe('/api/v1/meetings?scope=upcoming&page=0');
  });

  it('maps fetch rejections to a NETWORK_ERROR ApiError', async () => {
    const fetchImpl = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    });
    const client = new ApiClient({ fetchImpl: fetchImpl as unknown as typeof fetch });
    await expect(client.get('/x', { auth: 'none' })).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 0 });
  });
});

describe('ApiClient refresh failures (W-03)', () => {
  it.each([
    ['429 rate limited', () => json(429, { code: 'RATE_LIMITED', detail: 'Slow down' }, { 'Retry-After': '5' }), 429],
    ['503 from the backend', () => json(503, { code: 'SERVER_ERROR', detail: 'Try again' }), 503],
    ['502 from a proxy (HTML body)', () => new Response('<html>Bad gateway</html>', { status: 502 }), 502],
  ])('a transient refresh failure (%s) keeps the session, does not redirect and throws a retryable error', async (_label, refreshRes, status) => {
    const { client } = setup((c) => (c.url.endsWith('/auth/refresh') ? refreshRes() : json(401, { code: 'TOKEN_EXPIRED' })));
    const onFail = vi.fn();
    const onAuth = vi.fn();
    client.setAuthFailureHandler(onFail);
    client.setSession(auth('tok-1'));
    client.onAuthChange(onAuth);
    await expect(client.get('/meetings/m1/moderation/waiting')).rejects.toMatchObject({ status });
    expect(onFail).not.toHaveBeenCalled();
    expect(onAuth).not.toHaveBeenCalled();
    expect(client.getAccessToken()).toBe('tok-1');
  });

  it('a network error during refresh keeps the session and surfaces NETWORK_ERROR', async () => {
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith('/auth/refresh')) throw new TypeError('Failed to fetch');
      return json(401, { code: 'TOKEN_EXPIRED' });
    });
    const client = new ApiClient({ fetchImpl: fetchImpl as unknown as typeof fetch });
    const onFail = vi.fn();
    client.setAuthFailureHandler(onFail);
    client.setSession(auth('tok-1'));
    await expect(client.get('/x')).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 0 });
    expect(onFail).not.toHaveBeenCalled();
    expect(client.getAccessToken()).toBe('tok-1');
  });

  it('the session recovers on the next call once refresh works again', async () => {
    let refreshOk = false;
    const { client } = setup((c) => {
      if (c.url.endsWith('/auth/refresh')) return refreshOk ? json(200, auth('tok-2')) : json(503, {});
      return header(c, 'Authorization') === 'Bearer tok-2' ? json(200, { ok: true }) : json(401, { code: 'TOKEN_EXPIRED' });
    });
    client.setSession(auth('tok-1'));
    await expect(client.get('/x')).rejects.toMatchObject({ status: 503 });
    refreshOk = true;
    await expect(client.get('/x')).resolves.toEqual({ ok: true });
  });

  it('a 403 from refresh is an auth rejection: clears the session and redirects', async () => {
    const { client } = setup((c) => (c.url.endsWith('/auth/refresh') ? json(403, { code: 'ACCOUNT_DISABLED' }) : json(401, { code: 'TOKEN_EXPIRED' })));
    const onFail = vi.fn();
    client.setAuthFailureHandler(onFail);
    client.setSession(auth('tok-1'));
    await expect(client.get('/x')).rejects.toMatchObject({ status: 401 });
    expect(onFail).toHaveBeenCalledTimes(1);
    expect(client.getAccessToken()).toBeNull();
  });

  it('bootstrap refresh() resolves to null on a transient failure without clearing listeners state', async () => {
    const { client } = setup(() => json(500, {}));
    const onAuth = vi.fn();
    client.onAuthChange(onAuth);
    await expect(client.refresh()).resolves.toBeNull();
    expect(onAuth).not.toHaveBeenCalled();
  });
});

describe('ApiClient cross-tab refresh serialization (W-04)', () => {
  /** Minimal Web Locks stand-in shared by all "tabs" (clients) of the test. */
  function installLocks() {
    const queues = new Map<string, Promise<unknown>>();
    const request = vi.fn((name: string, cb: () => Promise<unknown>) => {
      const prev = queues.get(name) ?? Promise.resolve();
      const run = prev.then(cb, cb);
      queues.set(name, run.catch(() => undefined));
      return run;
    });
    Object.defineProperty(navigator, 'locks', { value: { request }, configurable: true });
    return { request, uninstall: () => Reflect.deleteProperty(navigator, 'locks') };
  }

  it('two tabs refreshing at once take turns, so the second sends the cookie rotated by the first', async () => {
    const locks = installLocks();
    try {
      // Simulated server with token rotation: only the current cookie value is accepted.
      let cookie = 'r1';
      let inFlight = 0;
      let maxInFlight = 0;
      const tab = () =>
        setup(async (c) => {
          if (c.url.endsWith('/auth/refresh')) {
            inFlight++;
            maxInFlight = Math.max(maxInFlight, inFlight);
            const sent = cookie;
            await new Promise((r) => setTimeout(r, 10));
            inFlight--;
            if (sent !== cookie) return json(401, { code: 'TOKEN_INVALID' });
            cookie = `${cookie}+`; // rotate (the browser stores the new cookie for every tab)
            return json(200, auth(`access-${cookie}`));
          }
          return json(200, {});
        }).client;
      const [a, b] = [tab(), tab()];
      const [ra, rb] = await Promise.all([a.refresh(), b.refresh()]);
      expect(ra).not.toBeNull();
      expect(rb).not.toBeNull();
      expect(maxInFlight).toBe(1);
      expect(locks.request).toHaveBeenCalledTimes(2);
      expect(locks.request.mock.calls[0]![0]).toBe('mp-auth-refresh');
    } finally {
      locks.uninstall();
    }
  });

  it('falls back to an unserialized refresh when Web Locks are unavailable (jsdom)', async () => {
    expect((navigator as { locks?: unknown }).locks).toBeUndefined();
    const { client } = setup(() => json(200, auth('fresh')));
    await expect(client.refresh()).resolves.toMatchObject({ accessToken: 'fresh' });
  });

  it('falls back to an unserialized refresh when the lock request itself fails', async () => {
    Object.defineProperty(navigator, 'locks', {
      value: { request: vi.fn(() => Promise.reject(new DOMException('denied', 'SecurityError'))) },
      configurable: true,
    });
    try {
      const { client } = setup(() => json(200, auth('fresh')));
      await expect(client.refresh()).resolves.toMatchObject({ accessToken: 'fresh' });
    } finally {
      Reflect.deleteProperty(navigator, 'locks');
    }
  });
});
