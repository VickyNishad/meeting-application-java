import { describe, expect, it } from 'vitest';
import { isRoomRecording, recordingSubject } from './recordings';

describe('recordingSubject', () => {
  it('names the whole meeting, a signed-in person and a guest', () => {
    expect(recordingSubject({ kind: 'CLOUD' })).toBe('Whole meeting');
    expect(recordingSubject({ kind: 'PARTICIPANT', subjectName: 'Asha', subjectUserId: 'u1' })).toBe('Asha');
    expect(recordingSubject({ kind: 'PARTICIPANT', subjectName: 'Visitor' })).toBe('Visitor (guest)');
    expect(recordingSubject({ kind: 'PARTICIPANT', subjectName: '  ', subjectUserId: 'u1' })).toBe('Participant');
  });

  it('tells room recordings from per-person ones', () => {
    expect(isRoomRecording({ kind: 'CLOUD' })).toBe(true);
    expect(isRoomRecording({ kind: 'PARTICIPANT' })).toBe(false);
  });
});
