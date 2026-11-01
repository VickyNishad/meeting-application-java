import type { JoinResponse } from './api/types';

/**
 * Keeps the JoinResponse of the current meeting in memory and the participant credentials
 * (participantId + waitToken) in sessionStorage so that a page refresh of the room can
 * re-obtain a LiveKit token via GET join-status. Nothing here goes to localStorage.
 * The user's latest in-meeting mic/camera choice is stored with it, so a reconnect, rejoin or page
 * refresh never turns a device back on that the user had turned off (unknown → both off).
 */
export interface StoredParticipant {
  participantId: string;
  waitToken: string;
  /** For sending a guest back through the /j/{joinCode} pre-join when the participant can't be resumed. */
  joinCode?: string;
}

/** Whether the microphone / camera should be published when (re)connecting to the room. */
export interface MediaState {
  audioEnabled: boolean;
  videoEnabled: boolean;
}

interface StoredJoin extends StoredParticipant {
  media?: MediaState;
}

export interface DevicePrefs {
  audioEnabled: boolean;
  videoEnabled: boolean;
  audioDeviceId?: string;
  videoDeviceId?: string;
  audioOutputDeviceId?: string;
}

const memory = new Map<string, JoinResponse>();
const obtainedAt = new Map<string, number>();
/** In-memory copy of the media state per meeting (mirrored into sessionStorage). */
const mediaMemory = new Map<string, MediaState>();
/** LiveKit tokens live ≤ 10 min; re-fetch via join-status when the cached one is older than this. */
const FRESH_MS = 4 * 60_000;
/** Pre-join defaults: the pre-join screen shows a preview and nothing is published until the user clicks Join. */
let devicePrefs: DevicePrefs = { audioEnabled: true, videoEnabled: true };

const key = (meetingId: string) => `mp-join:${meetingId}`;

function readStored(meetingId: string): Partial<StoredJoin> | null {
  try {
    const raw = window.sessionStorage.getItem(key(meetingId));
    return raw ? (JSON.parse(raw) as Partial<StoredJoin>) : null;
  } catch {
    return null;
  }
}

function writeStored(meetingId: string, stored: StoredJoin): void {
  try {
    window.sessionStorage.setItem(key(meetingId), JSON.stringify(stored));
  } catch {
    // sessionStorage unavailable (private mode / blocked): refresh-recovery just won't work.
  }
}

function isMediaState(v: unknown): v is MediaState {
  const m = v as Partial<MediaState> | null | undefined;
  return !!m && typeof m.audioEnabled === 'boolean' && typeof m.videoEnabled === 'boolean';
}

/** Saves the join. Keeps the media state already recorded for this meeting: a resume is not a new choice. */
export function saveJoin(meetingId: string, join: JoinResponse): void {
  memory.set(meetingId, join);
  obtainedAt.set(meetingId, Date.now());
  const media = mediaMemory.get(meetingId) ?? readStored(meetingId)?.media;
  writeStored(meetingId, {
    participantId: join.participantId,
    waitToken: join.waitToken,
    joinCode: join.meeting?.joinCode,
    media: isMediaState(media) ? media : undefined,
  });
}

export function getJoin(meetingId: string): JoinResponse | undefined {
  return memory.get(meetingId);
}

/** An ADMITTED join whose LiveKit token is still fresh enough to connect with. */
export function getFreshAdmittedJoin(meetingId: string): JoinResponse | undefined {
  const j = memory.get(meetingId);
  const at = obtainedAt.get(meetingId) ?? 0;
  if (j && j.status === 'ADMITTED' && j.token && j.livekitUrl && Date.now() - at < FRESH_MS) return j;
  return undefined;
}

export function getStoredParticipant(meetingId: string): StoredParticipant | null {
  const j = memory.get(meetingId);
  if (j) return { participantId: j.participantId, waitToken: j.waitToken, joinCode: j.meeting?.joinCode };
  const parsed = readStored(meetingId);
  if (parsed && typeof parsed.participantId === 'string' && typeof parsed.waitToken === 'string') {
    return {
      participantId: parsed.participantId,
      waitToken: parsed.waitToken,
      joinCode: typeof parsed.joinCode === 'string' ? parsed.joinCode : undefined,
    };
  }
  return null;
}

export function clearJoin(meetingId: string): void {
  memory.delete(meetingId);
  obtainedAt.delete(meetingId);
  mediaMemory.delete(meetingId);
  try {
    window.sessionStorage.removeItem(key(meetingId));
  } catch {
    // ignore
  }
}

/** Records the user's mic/camera choice for this meeting (pre-join choice, in-meeting toggles, device failures). */
export function saveMediaState(meetingId: string, media: MediaState): void {
  const next: MediaState = { audioEnabled: media.audioEnabled, videoEnabled: media.videoEnabled };
  mediaMemory.set(meetingId, next);
  // A later pre-join (rejoin) starts from the latest choice too.
  devicePrefs = { ...devicePrefs, ...next };
  const stored = readStored(meetingId);
  if (stored && typeof stored.participantId === 'string' && typeof stored.waitToken === 'string') {
    writeStored(meetingId, { ...stored, participantId: stored.participantId, waitToken: stored.waitToken, media: next });
  }
}

/** The mic/camera state to (re)connect with; both off when nothing is known (e.g. a reload without a record). */
export function getMediaState(meetingId: string): MediaState {
  const m = mediaMemory.get(meetingId) ?? readStored(meetingId)?.media;
  return isMediaState(m) ? { audioEnabled: m.audioEnabled, videoEnabled: m.videoEnabled } : { audioEnabled: false, videoEnabled: false };
}

export function setDevicePrefs(prefs: DevicePrefs): void {
  devicePrefs = prefs;
}

export function getDevicePrefs(): DevicePrefs {
  return devicePrefs;
}
