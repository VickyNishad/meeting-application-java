import { describe, expect, it } from 'vitest';
import type { JoinResponse, Meeting, RoomStateView } from '@/lib/api/types';
import { initialRoomState, isOnStage, roomReducer, stageFromMetadata, type RoomState } from './roomState';
import { roleFromMetadata } from './roomData';

const meeting = {
  id: 'm1',
  locked: false,
  chatEnabled: true,
  screenSharePolicy: 'EVERYONE',
  waitingRoomEnabled: false,
  recordingInProgress: false,
  visibilityMode: 'HOST_AND_SELF_ONLY',
} as Meeting;
const join = { participantId: 'me', role: 'PARTICIPANT', meeting } as JoinResponse;

function view(over: Partial<RoomStateView> = {}): RoomStateView {
  return {
    seq: 5,
    participantId: 'me',
    role: 'PARTICIPANT',
    visibilityMode: 'HOST_AND_SELF_ONLY',
    stageIdentities: ['host'],
    locked: false,
    chatEnabled: true,
    screenSharePolicy: 'EVERYONE',
    waitingRoomEnabled: false,
    cloudRecording: false,
    autoRecord: false,
    localRecorders: [],
    raisedHands: [],
    ...over,
  };
}

const signal = (topic: string, raw: Record<string, unknown>) => ({ type: 'signal' as const, topic, raw, selfId: 'me' });
const loaded = (over: Partial<RoomStateView> = {}): RoomState => roomReducer(initialRoomState(meeting, join), { type: 'snapshot', view: view(over) });

describe('room state store (SYNC-2)', () => {
  it('takes everything from the snapshot', () => {
    const s = loaded({ locked: true, cloudRecording: true, raisedHands: [{ participantId: 'a', displayName: 'Ann', role: 'GUEST' }] });
    expect(s.loaded).toBe(true);
    expect(s.seq).toBe(5);
    expect(s.locked).toBe(true);
    expect(s.cloudRecording).toBe(true);
    expect(s.raisedHands).toEqual({ a: 'Ann' });
    expect(s.stageSeq).toBeNull();
  });

  it('applies the next numbered signal and ignores old or duplicate ones', () => {
    let s = loaded();
    s = roomReducer(s, signal('moderation', { type: 'room-locked', locked: true, stream: 'all', seq: 6 }));
    expect(s.locked).toBe(true);
    expect(s.seq).toBe(6);
    s = roomReducer(s, signal('moderation', { type: 'room-locked', locked: false, stream: 'all', seq: 4 }));
    expect(s.locked).toBe(true);
    expect(s.stale).toBe(false);
  });

  it('marks the store stale when a numbered signal was skipped', () => {
    const s = roomReducer(loaded(), signal('recording', { type: 'recording', kind: 'CLOUD', state: 'STARTED', stream: 'all', seq: 8 }));
    expect(s.cloudRecording).toBe(true);
    expect(s.stale).toBe(true);
    // The reloaded snapshot clears it.
    expect(roomReducer(s, { type: 'snapshot', view: view({ seq: 8, cloudRecording: true }) }).stale).toBe(false);
  });

  it('holds numbered signals that arrive before the snapshot and applies only the newer ones', () => {
    let s = initialRoomState(meeting, join);
    s = roomReducer(s, signal('moderation', { type: 'room-locked', locked: true, stream: 'all', seq: 5 }));
    s = roomReducer(s, signal('hand', { type: 'hand', participantId: 'host', displayName: 'Hal', raised: true, stream: 'all', seq: 6 }));
    expect(s.pending).toHaveLength(2);
    s = roomReducer(s, { type: 'snapshot', view: view({ seq: 5, locked: false }) });
    // seq 5 is already in the snapshot (locked=false there wins); seq 6 is applied on top.
    expect(s.locked).toBe(false);
    expect(s.raisedHands).toEqual({ host: 'Hal' });
    expect(s.seq).toBe(6);
    expect(s.pending).toEqual([]);
  });

  it('applies an attendee echo on the stage stream without numbering when not on the stage', () => {
    const s = roomReducer(loaded(), signal('hand', { type: 'hand', participantId: 'me', displayName: 'Me', raised: true, stream: 'stage', seq: 40 }));
    expect(s.raisedHands).toEqual({ me: 'Me' });
    expect(s.stale).toBe(false);
  });

  it('numbers the stage stream for the stage', () => {
    let s = loaded({ role: 'HOST', stageSeq: 10 });
    s = roomReducer(s, signal('hand', { type: 'hand', participantId: 'a', displayName: 'Ann', raised: true, stream: 'stage', seq: 11 }));
    expect(s.stageSeq).toBe(11);
    expect(s.stale).toBe(false);
    s = roomReducer(s, signal('hand', { type: 'hand', participantId: 'a', raised: false, stream: 'stage', seq: 13 }));
    expect(s.stale).toBe(true);
    expect(s.raisedHands).toEqual({});
  });

  it('tracks the stage and reloads when this participant joins or leaves it', () => {
    let s = loaded();
    s = roomReducer(s, signal('moderation', { type: 'role-changed', participantId: 'b', role: 'CO_HOST', stream: 'all', seq: 6 }));
    expect(s.stageIdentities).toEqual(['host', 'b']);
    expect(s.stale).toBe(false);
    s = roomReducer(s, signal('moderation', { type: 'role-changed', participantId: 'me', role: 'CO_HOST', stream: 'all', seq: 7 }));
    expect(s.role).toBe('CO_HOST');
    expect(s.stale).toBe(true);
    s = roomReducer(s, signal('moderation', { type: 'role-changed', participantId: 'b', role: 'PARTICIPANT', stream: 'all', seq: 8 }));
    expect(s.stageIdentities).toEqual(['host', 'me']);
  });

  it('adds and clears local recorders (PRIV-3)', () => {
    let s = loaded();
    s = roomReducer(s, signal('recording', { type: 'recording', kind: 'LOCAL', state: 'STARTED', participantId: 'x', displayName: 'Xi', stream: 'all', seq: 6 }));
    expect(s.localRecorders).toEqual({ x: 'Xi' });
    s = roomReducer(s, signal('recording', { type: 'recording', kind: 'LOCAL', state: 'STOPPED', participantId: 'x', stream: 'all', seq: 7 }));
    expect(s.localRecorders).toEqual({});
  });

  it('ignores per-person recording signals for the room indicator', () => {
    const s = roomReducer(loaded(), signal('recording', { type: 'recording', kind: 'PARTICIPANT', state: 'STARTED', participantId: 'x', stream: 'all', seq: 6 }));
    expect(s.cloudRecording).toBe(false);
  });
});

describe('room state store: QA fixes', () => {
  it('reloads when signals come from new server counters (QA-B1)', () => {
    let s = loaded({ epoch: 'e1', seq: 50 });
    s = roomReducer(s, signal('hand', { type: 'hand', participantId: 'host', displayName: 'Hal', raised: true, stream: 'all', seq: 1, epoch: 'e2' }));
    // A low number from new counters is not mistaken for an old signal: applied, and a reload is requested.
    expect(s.raisedHands).toEqual({ host: 'Hal' });
    expect(s.stale).toBe(true);
    s = roomReducer(s, { type: 'snapshot', view: view({ epoch: 'e2', seq: 1, raisedHands: [{ participantId: 'host', displayName: 'Hal', role: 'HOST' }] }) });
    expect(s.stale).toBe(false);
    s = roomReducer(s, signal('moderation', { type: 'room-locked', locked: true, stream: 'all', seq: 2, epoch: 'e2' }));
    expect(s.locked).toBe(true);
    expect(s.stale).toBe(false);
  });

  it('counts numbered topics the store does not use, so chat does not look like a missed signal (QA-F2)', () => {
    let s = loaded({ seq: 10 });
    s = roomReducer(s, signal('chat', { type: 'chat', message: {}, stream: 'all', seq: 11 }));
    s = roomReducer(s, signal('reactions', { type: 'reaction', emoji: '👍', stream: 'all', seq: 12 }));
    s = roomReducer(s, signal('files', { type: 'file', file: {}, stream: 'all', seq: 13 }));
    s = roomReducer(s, signal('moderation', { type: 'room-locked', locked: true, stream: 'all', seq: 14 }));
    expect(s.seq).toBe(14);
    expect(s.stale).toBe(false);
  });

  it('never lets an older snapshot undo a signal received while reloading (QA-F7)', () => {
    let s = loaded({ seq: 5 });
    s = roomReducer(s, signal('hand', { type: 'hand', participantId: 'a', displayName: 'Ann', raised: true, stream: 'all', seq: 7 }));
    expect(s.stale).toBe(true);
    // While the reload is on its way, the room is locked (seq 8): shown at once and kept for replay.
    s = roomReducer(s, signal('moderation', { type: 'room-locked', locked: true, stream: 'all', seq: 8 }));
    expect(s.locked).toBe(true);
    // The snapshot was taken at seq 7 (before the lock).
    s = roomReducer(s, { type: 'snapshot', view: view({ seq: 7, locked: false, raisedHands: [{ participantId: 'a', displayName: 'Ann', role: 'GUEST' }] }) });
    expect(s.locked).toBe(true);
    expect(s.seq).toBe(8);
    expect(s.stale).toBe(false);
    expect(s.pending).toEqual([]);
  });
});

describe('room state store: Phase 6 (participant count and stale responses)', () => {
  it('takes the participant count from the server and follows presence signals', () => {
    let s = loaded({ seq: 3, presentCount: 4 });
    expect(s.presentCount).toBe(4);
    s = roomReducer(s, signal('presence', { type: 'presence', count: 5, stream: 'all', seq: 4 }));
    expect(s.presentCount).toBe(5);
    // A repeated (older) presence signal does not change it back.
    s = roomReducer(s, signal('presence', { type: 'presence', count: 4, stream: 'all', seq: 4 }));
    expect(s.presentCount).toBe(5);
    // Malformed counts are ignored.
    s = roomReducer(s, signal('presence', { type: 'presence', count: -1, stream: 'all', seq: 5 }));
    expect(s.presentCount).toBe(5);
  });

  it('drops a slow snapshot response that is older than what is already shown', () => {
    let s = loaded({ seq: 3, locked: false });
    s = roomReducer(s, signal('moderation', { type: 'room-locked', locked: true, stream: 'all', seq: 4 }));
    // A request sent before the lock answers late (seq 3), with no reload pending.
    const before = s;
    s = roomReducer(s, { type: 'snapshot', view: view({ seq: 3, locked: false }) });
    expect(s).toBe(before);
    expect(s.locked).toBe(true);
  });

  it('accepts an equal or newer snapshot', () => {
    let s = loaded({ seq: 3, locked: false });
    s = roomReducer(s, { type: 'snapshot', view: view({ seq: 6, locked: true, presentCount: 9 }) });
    expect(s.locked).toBe(true);
    expect(s.seq).toBe(6);
    expect(s.presentCount).toBe(9);
  });
});

describe('isOnStage (QA-F1)', () => {
  it('trusts server-set metadata for people who joined after the snapshot', () => {
    expect(isOnStage({ identity: 'late-host', metadata: '{"role":"HOST"}' }, roleFromMetadata, {}, [])).toBe(true);
    expect(isOnStage({ identity: 'a', metadata: '{"role":"PARTICIPANT"}' }, roleFromMetadata, {}, ['a'])).toBe(false);
    expect(isOnStage({ identity: 'b' }, roleFromMetadata, {}, ['b'])).toBe(true);
    expect(isOnStage({ identity: 'c', metadata: '{"role":"HOST"}' }, roleFromMetadata, { c: 'PARTICIPANT' }, [])).toBe(false);
  });
});

describe('stageFromMetadata', () => {
  it('uses server-set roles, with session overrides taking precedence', () => {
    const ps = [
      { identity: 'h', metadata: '{"role":"HOST"}' },
      { identity: 'c', metadata: '{"role":"CO_HOST"}' },
      { identity: 'a', metadata: '{"role":"PARTICIPANT"}' },
      { identity: 'x', metadata: 'not json' },
    ];
    expect(stageFromMetadata(ps, roleFromMetadata, {})).toEqual(['h', 'c']);
    expect(stageFromMetadata(ps, roleFromMetadata, { c: 'PARTICIPANT', a: 'CO_HOST' })).toEqual(['h', 'a']);
  });
});

describe('room state store: QA56 fixes', () => {
  it('QA56-7: drops a snapshot that is current on the all stream but older on the stage stream', () => {
    let s = loaded({ role: 'HOST', seq: 3, stageSeq: 10, presentCount: 5 });
    s = roomReducer(s, signal('presence', { type: 'presence', count: 9, stream: 'stage', seq: 11 }));
    s = roomReducer(s, signal('hand', { type: 'hand', participantId: 'a', displayName: 'Ann', raised: true, stream: 'stage', seq: 12 }));
    const before = s;
    s = roomReducer(s, { type: 'snapshot', view: view({ role: 'HOST', seq: 3, stageSeq: 10, presentCount: 5 }) });
    expect(s).toBe(before);
    expect(s.presentCount).toBe(9);
    expect(s.raisedHands).toEqual({ a: 'Ann' });
    expect(s.stageSeq).toBe(12);
  });

  it('QA56-9: an attendee applies its unnumbered count when the stage changes', () => {
    let s = loaded({ seq: 3, presentCount: 2 });
    s = roomReducer(s, signal('presence', { type: 'presence', count: 3 }));
    expect(s.presentCount).toBe(3);
    expect(s.stale).toBe(false);
    expect(s.seq).toBe(3);
  });
});

describe('media permissions in the room state', () => {
  const media = (participantId: string, mic: boolean, override: 'ALLOWED' | 'RESTRICTED' | null = null) => ({
    participantId,
    microphoneOverride: override,
    cameraOverride: null,
    screenShareOverride: null,
    microphone: mic,
    camera: true,
    screenShare: true,
  });

  it('takes the meeting-wide permissions and the rights from the snapshot (own, and everyone for the host)', () => {
    const s = loaded({
      mediaPermissions: { microphone: 'RESTRICTED', camera: 'ALLOWED', screenShare: 'ALLOWED' },
      myMedia: media('me', false),
      participantMedia: [media('a', true, 'ALLOWED')],
    });
    expect(s.mediaPermissions.microphone).toBe('RESTRICTED');
    expect(s.participantMedia.me?.microphone).toBe(false);
    expect(s.participantMedia.a?.microphoneOverride).toBe('ALLOWED');
  });

  it('applies a numbered media-permissions signal, keeping the screen-sharing policy in step, and ignores a stale one', () => {
    let s = loaded();
    s = roomReducer(s, signal('moderation', { type: 'media-permissions', microphone: 'RESTRICTED', camera: 'ALLOWED', screenShare: 'RESTRICTED', stream: 'all', seq: 6 }));
    expect(s.mediaPermissions).toEqual({ microphone: 'RESTRICTED', camera: 'ALLOWED', screenShare: 'RESTRICTED' });
    expect(s.screenSharePolicy).toBe('HOSTS_ONLY');
    // An older signal (out of order) does not undo it.
    s = roomReducer(s, signal('moderation', { type: 'media-permissions', microphone: 'ALLOWED', camera: 'ALLOWED', screenShare: 'ALLOWED', stream: 'all', seq: 5 }));
    expect(s.mediaPermissions.microphone).toBe('RESTRICTED');
  });

  it('ignores malformed media signals', () => {
    let s = loaded();
    s = roomReducer(s, signal('moderation', { type: 'media-permissions', microphone: 'MAYBE', camera: 'ALLOWED', screenShare: 'ALLOWED', stream: 'all', seq: 6 }));
    expect(s.mediaPermissions.microphone).toBe('ALLOWED');
    s = roomReducer(s, signal('moderation', { type: 'participant-media', participants: [{ participantId: 'x', microphone: 'yes' }] }));
    expect(s.participantMedia.x).toBeUndefined();
  });

  it('merges participant-media signals and the host’s own changes', () => {
    let s = loaded({ participantMedia: [media('a', true), media('b', true)] });
    s = roomReducer(s, signal('moderation', { type: 'participant-media', participants: [media('a', false, 'RESTRICTED')] }));
    expect(s.participantMedia.a?.microphone).toBe(false);
    expect(s.participantMedia.b?.microphone).toBe(true);
    s = roomReducer(s, { type: 'participant-media', media: media('b', false, 'RESTRICTED') });
    expect(s.participantMedia.b?.microphoneOverride).toBe('RESTRICTED');
  });

  it('keeps the screen-sharing permission and policy as one setting when the host changes either', () => {
    let s = loaded();
    s = roomReducer(s, { type: 'patch', patch: { mediaPermissions: { microphone: 'ALLOWED', camera: 'ALLOWED', screenShare: 'RESTRICTED' } } });
    expect(s.screenSharePolicy).toBe('HOSTS_ONLY');
    s = roomReducer(s, { type: 'patch', patch: { screenSharePolicy: 'EVERYONE' } });
    expect(s.mediaPermissions.screenShare).toBe('ALLOWED');
  });
});
