import { describe, expect, it } from 'vitest';
import { ApiError } from '@/lib/api/problem';
import { decodePacket, encodePacket, isTerminalJoinStatusError, mergeChat, parseChat, parseModeration, parseRecording, recordingNotice, roleFromMetadata } from './roomData';

describe('isTerminalJoinStatusError (W-05)', () => {
  const err = (status: number, code: string) => new ApiError({ status, code, title: '', detail: '' });
  it('stops on cancelled/ended/not found/forbidden, keeps polling on transient errors', () => {
    expect(isTerminalJoinStatusError(err(409, 'MEETING_CANCELLED'))).toBe(true);
    expect(isTerminalJoinStatusError(err(409, 'MEETING_ENDED'))).toBe(true);
    expect(isTerminalJoinStatusError(err(404, 'NOT_FOUND'))).toBe(true);
    expect(isTerminalJoinStatusError(err(403, 'FORBIDDEN'))).toBe(true);
    expect(isTerminalJoinStatusError(err(0, 'NETWORK_ERROR'))).toBe(false);
    expect(isTerminalJoinStatusError(err(429, 'RATE_LIMITED'))).toBe(false);
    expect(isTerminalJoinStatusError(err(503, 'SERVER_ERROR'))).toBe(false);
    expect(isTerminalJoinStatusError(new Error('x'))).toBe(false);
  });
});

describe('room data-channel parsing', () => {
  it('round-trips JSON packets', () => {
    expect(decodePacket(encodePacket({ type: 'hand', raised: true }))).toEqual({ type: 'hand', raised: true });
    expect(decodePacket(new TextEncoder().encode('not json'))).toBeNull();
  });

  it('parses moderation messages and rejects unknown ones', () => {
    expect(parseModeration({ type: 'request-unmute', source: 'MICROPHONE' })).toEqual({ type: 'request-unmute', source: 'MICROPHONE' });
    expect(parseModeration({ type: 'request-unmute', source: 'SPEAKER' })).toBeNull();
    expect(parseModeration({ type: 'removed' })).toEqual({ type: 'removed' });
    expect(parseModeration({ type: 'meeting-ended' })).toEqual({ type: 'meeting-ended' });
    expect(parseModeration({ type: 'room-locked', locked: true })).toEqual({ type: 'room-locked', locked: true });
    expect(parseModeration({ type: 'role-changed', participantId: 'p1', role: 'CO_HOST' })).toEqual({ type: 'role-changed', participantId: 'p1', role: 'CO_HOST' });
    expect(parseModeration({ type: 'settings', screenSharePolicy: 'HOSTS_ONLY' })).toEqual({ type: 'settings', screenSharePolicy: 'HOSTS_ONLY' });
    expect(parseModeration({ type: 'settings', chatEnabled: false })).toEqual({ type: 'settings', chatEnabled: false, screenSharePolicy: undefined, waitingRoomEnabled: undefined });
    expect(parseModeration({ type: 'settings' })).toBeNull();
    expect(parseModeration({ type: 'unmute-now' })).toBeNull();
  });

  it('parses recording indicator messages', () => {
    expect(parseRecording({ type: 'recording', kind: 'LOCAL', state: 'STARTED', participantId: 'p', displayName: 'Ana' })).toEqual({
      type: 'recording',
      kind: 'LOCAL',
      state: 'STARTED',
      participantId: 'p',
      displayName: 'Ana',
    });
    expect(parseRecording({ type: 'recording', kind: 'CLOUD', state: 'PAUSED' })).toBeNull();
  });

  it('parses auto-record participant notices and rejects unknown kinds', () => {
    expect(parseRecording({ type: 'recording', kind: 'PARTICIPANT', state: 'STARTED', participantId: 'p2', displayName: 'Ravi', auto: true })).toEqual({
      type: 'recording',
      kind: 'PARTICIPANT',
      state: 'STARTED',
      participantId: 'p2',
      displayName: 'Ravi',
    });
    expect(parseRecording({ type: 'recording', kind: 'SECRET', state: 'STARTED' })).toBeNull();
  });

  it('parses chat and merges/dedupes by id in time order', () => {
    const m1 = parseChat({ type: 'chat', message: { id: '1', text: 'hi', sentAt: '2026-01-01T00:00:01Z', senderName: 'A', participantId: 'p' } })!;
    const m2 = { ...m1, id: '2', sentAt: '2026-01-01T00:00:00Z' };
    expect(mergeChat([m1], [m2, m1]).map((m) => m.id)).toEqual(['2', '1']);
    expect(parseChat({ type: 'chat', message: { id: 3 } })).toBeNull();
  });

  it('reads the role from server-set participant metadata', () => {
    expect(roleFromMetadata('{"role":"HOST","userId":"u"}')).toBe('HOST');
    expect(roleFromMetadata('{"role":"ADMIN"}')).toBeUndefined();
    expect(roleFromMetadata('garbage')).toBeUndefined();
    expect(roleFromMetadata(undefined)).toBeUndefined();
  });
});

describe('recordingNotice (QA56-12)', () => {
  it('gives every notice a stable id, so the same notice arriving twice is shown once', () => {
    const started = parseRecording({ type: 'recording', kind: 'CLOUD', state: 'STARTED' })!;
    const once = recordingNotice(started, 'me', false);
    const again = recordingNotice(parseRecording({ type: 'recording', kind: 'CLOUD', state: 'STARTED', seq: 9, stream: 'all' })!, 'me', false);
    expect(once).toEqual({ level: 'info', text: 'Cloud recording started', id: 'cloud-recording-STARTED' });
    expect(again?.id).toBe(once?.id);
    const local = recordingNotice(parseRecording({ type: 'recording', kind: 'LOCAL', state: 'STARTED', participantId: 'x', displayName: 'Xi' })!, 'me', false);
    expect(local).toEqual({ level: 'info', text: 'Xi started recording locally', id: 'local-recording-x' });
  });

  it('stays quiet where it was quiet before', () => {
    expect(recordingNotice(parseRecording({ type: 'recording', kind: 'CLOUD', state: 'STARTED' })!, 'me', true)).toBeNull();
    expect(recordingNotice(parseRecording({ type: 'recording', kind: 'LOCAL', state: 'STARTED', participantId: 'me' })!, 'me', false)).toBeNull();
    expect(recordingNotice(parseRecording({ type: 'recording', kind: 'PARTICIPANT', state: 'STOPPED', participantId: 'x' })!, 'me', false)).toBeNull();
    expect(recordingNotice(parseRecording({ type: 'recording', kind: 'CLOUD', state: 'FAILED' })!, 'me', false)?.level).toBe('error');
  });
});
