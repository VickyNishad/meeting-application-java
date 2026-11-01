import { beforeEach, describe, expect, it } from 'vitest';
import type { JoinResponse, Meeting } from './api/types';
import { clearJoin, getDevicePrefs, getMediaState, getStoredParticipant, saveJoin, saveMediaState, setDevicePrefs } from './joinStore';

const join = (overrides: Partial<JoinResponse> = {}): JoinResponse => ({
  status: 'ADMITTED',
  participantId: 'p1',
  waitToken: 'w1',
  role: 'GUEST',
  token: 'lk',
  livekitUrl: 'wss://lk',
  meeting: { id: 'm1', joinCode: 'abc-defg-hij' } as Meeting,
  ...overrides,
});

describe('joinStore media state (W-02)', () => {
  beforeEach(() => {
    clearJoin('m1');
    window.sessionStorage.clear();
    setDevicePrefs({ audioEnabled: true, videoEnabled: true });
  });

  it('defaults to both OFF when nothing is known (e.g. after a reload)', () => {
    expect(getMediaState('m1')).toEqual({ audioEnabled: false, videoEnabled: false });
    saveJoin('m1', join());
    expect(getMediaState('m1')).toEqual({ audioEnabled: false, videoEnabled: false });
  });

  it('persists the latest choice with the join in sessionStorage and keeps it across a resume (saveJoin)', () => {
    saveJoin('m1', join());
    saveMediaState('m1', { audioEnabled: true, videoEnabled: true });
    saveMediaState('m1', { audioEnabled: false, videoEnabled: true }); // muted in the meeting
    saveJoin('m1', join({ token: 'lk-2' })); // Reconnect → join-status → saveJoin again
    expect(getMediaState('m1')).toEqual({ audioEnabled: false, videoEnabled: true });
    const raw = JSON.parse(window.sessionStorage.getItem('mp-join:m1') ?? '{}');
    expect(raw.media).toEqual({ audioEnabled: false, videoEnabled: true });
    expect(window.localStorage.length).toBe(0);
  });

  it('reads the stored state after a reload (module memory gone, sessionStorage kept)', () => {
    window.sessionStorage.setItem(
      'mp-join:m2',
      JSON.stringify({ participantId: 'p9', waitToken: 'w9', joinCode: 'code-9', media: { audioEnabled: true, videoEnabled: false } }),
    );
    expect(getMediaState('m2')).toEqual({ audioEnabled: true, videoEnabled: false });
    expect(getStoredParticipant('m2')).toEqual({ participantId: 'p9', waitToken: 'w9', joinCode: 'code-9' });
    clearJoin('m2');
  });

  it('ignores a malformed stored media value (→ off)', () => {
    window.sessionStorage.setItem('mp-join:m3', JSON.stringify({ participantId: 'p', waitToken: 'w', media: { audioEnabled: 'yes' } }));
    expect(getMediaState('m3')).toEqual({ audioEnabled: false, videoEnabled: false });
    clearJoin('m3');
  });

  it('a later pre-join (rejoin) starts from the latest in-meeting choice', () => {
    saveJoin('m1', join());
    saveMediaState('m1', { audioEnabled: false, videoEnabled: false });
    expect(getDevicePrefs()).toMatchObject({ audioEnabled: false, videoEnabled: false });
  });

  it('stores the join code for sending guests back to /j/{joinCode}; clearJoin forgets everything', () => {
    saveJoin('m1', join());
    saveMediaState('m1', { audioEnabled: true, videoEnabled: false });
    expect(getStoredParticipant('m1')).toMatchObject({ joinCode: 'abc-defg-hij' });
    clearJoin('m1');
    expect(getStoredParticipant('m1')).toBeNull();
    expect(getMediaState('m1')).toEqual({ audioEnabled: false, videoEnabled: false });
  });
});
