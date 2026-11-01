import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';
import { meetingsApi } from './endpoints';

describe('meetingsApi.joinStatus (W-10)', () => {
  afterEach(() => vi.restoreAllMocks());

  it('sends the participant secret only in X-Participant-Token, never in the query string', async () => {
    const get = vi.spyOn(api, 'get').mockResolvedValue({});
    await meetingsApi.joinStatus('m1', 'p1', 'secret-wait-token');
    const [path, opts] = get.mock.calls[0]!;
    expect(path).toBe('/meetings/m1/join-status');
    expect(opts?.query).toEqual({ participantId: 'p1' });
    expect(opts?.headers).toEqual({ 'X-Participant-Token': 'secret-wait-token' });
    expect(api.buildUrl(path, opts?.query)).not.toContain('secret-wait-token');
  });
});
