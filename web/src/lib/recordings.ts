import type { Recording } from '@/lib/api/types';

/** Whose recording this is: the whole meeting, or one person's own camera and microphone. */
export function recordingSubject(r: Pick<Recording, 'kind' | 'subjectName' | 'subjectUserId'>): string {
  if (r.kind !== 'PARTICIPANT') return 'Whole meeting';
  const name = r.subjectName?.trim() || 'Participant';
  return r.subjectUserId ? name : `${name} (guest)`;
}

export function isRoomRecording(r: Pick<Recording, 'kind'>): boolean {
  return r.kind !== 'PARTICIPANT';
}
