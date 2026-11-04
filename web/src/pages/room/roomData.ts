import type { Participant } from 'livekit-client';
import type { ChatMessage, MediaPermission, MediaPermissions, MeetingRole, ParticipantMedia, ScreenSharePolicy } from '@/lib/api/types';
import { ApiError } from '@/lib/api/problem';

/** Data-channel contract (docs/04-api-contracts.md §16). */
export const TOPIC = {
  chat: 'chat',
  moderation: 'moderation',
  recording: 'recording',
  reactions: 'reactions',
  hand: 'hand',
  files: 'files',
  /** `{ type: "presence", count }`: how many people are in the session now (server count). */
  presence: 'presence',
} as const;

/** The contract specifies "an allow-list of 8 emoji" without enumerating them; this is our list. */
export const REACTION_EMOJI = ['👍', '👏', '❤️', '😂', '😮', '🎉', '🙌', '🤔'] as const;

export type ChatPacket = { type: 'chat'; message: ChatMessage };
export type ModerationPacket =
  | { type: 'request-unmute'; source: 'MICROPHONE' | 'CAMERA' }
  | { type: 'removed' }
  | { type: 'meeting-ended' }
  | { type: 'room-locked'; locked: boolean }
  | { type: 'role-changed'; participantId: string; role: MeetingRole }
  | { type: 'waiting-room'; count: number }
  | { type: 'settings'; screenSharePolicy?: ScreenSharePolicy; chatEnabled?: boolean; waitingRoomEnabled?: boolean }
  /** The meeting-wide media permissions changed (everyone). */
  | ({ type: 'media-permissions' } & MediaPermissions)
  /** Participants' media rights: the host gets every changed participant's, a participant its own. */
  | { type: 'participant-media'; participants: ParticipantMedia[] };
export type RecordingPacket = {
  type: 'recording';
  /** CLOUD = whole room, PARTICIPANT = one person's auto-recording (participantId), LOCAL = someone's browser. */
  kind: 'CLOUD' | 'PARTICIPANT' | 'LOCAL';
  state: 'STARTED' | 'STOPPED' | 'FAILED';
  participantId?: string;
  displayName?: string;
};
export type ReactionPacket = { type: 'reaction'; emoji: string };
export type HandPacket = { type: 'hand'; raised: boolean };

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function encodePacket(p: object): Uint8Array {
  return encoder.encode(JSON.stringify(p));
}

export function decodePacket(payload: Uint8Array): Record<string, unknown> | null {
  try {
    const v: unknown = JSON.parse(decoder.decode(payload));
    return typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/**
 * Server-originated packets arrive without a sending participant. Participants' tokens do not allow publishing data
 * at all, so every packet on the room's topics comes from the server; this check stays as a second line of defence.
 */
export function isFromServer(from: Participant | undefined): boolean {
  return from === undefined;
}

export function parseModeration(raw: Record<string, unknown> | null): ModerationPacket | null {
  if (!raw || typeof raw.type !== 'string') return null;
  switch (raw.type) {
    case 'request-unmute':
      return raw.source === 'MICROPHONE' || raw.source === 'CAMERA' ? { type: 'request-unmute', source: raw.source } : null;
    case 'removed':
    case 'meeting-ended':
      return { type: raw.type };
    case 'room-locked':
      return { type: 'room-locked', locked: raw.locked === true };
    case 'role-changed':
      return typeof raw.participantId === 'string' && typeof raw.role === 'string'
        ? { type: 'role-changed', participantId: raw.participantId, role: raw.role as MeetingRole }
        : null;
    case 'waiting-room':
      return { type: 'waiting-room', count: typeof raw.count === 'number' ? raw.count : 0 };
    case 'settings': {
      const policy = raw.screenSharePolicy === 'EVERYONE' || raw.screenSharePolicy === 'HOSTS_ONLY' ? raw.screenSharePolicy : undefined;
      const chat = typeof raw.chatEnabled === 'boolean' ? raw.chatEnabled : undefined;
      const waiting = typeof raw.waitingRoomEnabled === 'boolean' ? raw.waitingRoomEnabled : undefined;
      if (policy === undefined && chat === undefined && waiting === undefined) return null;
      return { type: 'settings', screenSharePolicy: policy, chatEnabled: chat, waitingRoomEnabled: waiting };
    }
    case 'media-permissions': {
      const microphone = mediaPermission(raw.microphone);
      const camera = mediaPermission(raw.camera);
      const screenShare = mediaPermission(raw.screenShare);
      return microphone && camera && screenShare ? { type: 'media-permissions', microphone, camera, screenShare } : null;
    }
    case 'participant-media': {
      if (!Array.isArray(raw.participants)) return null;
      const participants = raw.participants.map(parseParticipantMedia).filter((p): p is ParticipantMedia => p !== null);
      return { type: 'participant-media', participants };
    }
    default:
      return null;
  }
}

function mediaPermission(v: unknown): MediaPermission | null {
  return v === 'ALLOWED' || v === 'RESTRICTED' ? v : null;
}

export function parseParticipantMedia(v: unknown): ParticipantMedia | null {
  const m = v as Record<string, unknown> | null;
  if (!m || typeof m.participantId !== 'string') return null;
  if (typeof m.microphone !== 'boolean' || typeof m.camera !== 'boolean' || typeof m.screenShare !== 'boolean') return null;
  return {
    participantId: m.participantId,
    microphoneOverride: mediaPermission(m.microphoneOverride),
    cameraOverride: mediaPermission(m.cameraOverride),
    screenShareOverride: mediaPermission(m.screenShareOverride),
    microphone: m.microphone,
    camera: m.camera,
    screenShare: m.screenShare,
  };
}

export function parseRecording(raw: Record<string, unknown> | null): RecordingPacket | null {
  if (!raw || raw.type !== 'recording') return null;
  if (raw.kind !== 'CLOUD' && raw.kind !== 'PARTICIPANT' && raw.kind !== 'LOCAL') return null;
  if (raw.state !== 'STARTED' && raw.state !== 'STOPPED' && raw.state !== 'FAILED') return null;
  return {
    type: 'recording',
    kind: raw.kind,
    state: raw.state,
    participantId: typeof raw.participantId === 'string' ? raw.participantId : undefined,
    displayName: typeof raw.displayName === 'string' ? raw.displayName : undefined,
  };
}

/** A notice to show for a recording signal: `id` makes the same notice arriving twice show once (QA56-12). */
export interface RecordingNotice {
  level: 'info' | 'error';
  text: string;
  id: string;
}

/**
 * What to tell this participant about a recording signal, or null. Cloud recordings are announced unless auto-record
 * already shows a permanent notice; older per-person recordings only to that person (when stopped); someone else
 * starting a local recording to everyone who receives it. The same notice is sent once at once and again numbered,
 * so every notice carries a stable id.
 */
export function recordingNotice(p: RecordingPacket, selfId: string, autoRecord: boolean): RecordingNotice | null {
  if (p.kind === 'CLOUD') {
    if (autoRecord) return null;
    const id = `cloud-recording-${p.state}`;
    if (p.state === 'STARTED') return { level: 'info', text: 'Cloud recording started', id };
    if (p.state === 'STOPPED') return { level: 'info', text: 'Cloud recording stopped', id };
    return { level: 'error', text: 'Cloud recording failed', id };
  }
  if (p.kind === 'PARTICIPANT') {
    if (p.participantId !== selfId || p.state !== 'STOPPED') return null;
    return { level: 'info', text: 'Your individual recording was stopped', id: 'my-auto-recording' };
  }
  if (p.participantId && p.state === 'STARTED' && p.participantId !== selfId) {
    return { level: 'info', text: `${p.displayName ?? 'A participant'} started recording locally`, id: `local-recording-${p.participantId}` };
  }
  return null;
}

export function parseChat(raw: Record<string, unknown> | null): ChatMessage | null {
  if (!raw || raw.type !== 'chat') return null;
  const m = raw.message as Partial<ChatMessage> | undefined;
  if (!m || typeof m.id !== 'string' || typeof m.text !== 'string') return null;
  return {
    id: m.id,
    meetingId: String(m.meetingId ?? ''),
    participantId: String(m.participantId ?? ''),
    senderName: String(m.senderName ?? 'Unknown'),
    text: m.text,
    sentAt: String(m.sentAt ?? new Date().toISOString()),
    audience: m.audience === 'STAGE' ? 'STAGE' : 'ALL',
  };
}

/** Role from LiveKit participant metadata (`{"role":"HOST","userId":"…"}`), set by the server. */
export function roleFromMetadata(metadata: string | undefined): MeetingRole | undefined {
  if (!metadata) return undefined;
  try {
    const m = JSON.parse(metadata) as { role?: unknown };
    return m.role === 'HOST' || m.role === 'CO_HOST' || m.role === 'PARTICIPANT' || m.role === 'GUEST' ? m.role : undefined;
  } catch {
    return undefined;
  }
}

export function isModeratorRole(role: MeetingRole | undefined): boolean {
  return role === 'HOST' || role === 'CO_HOST';
}

export function mergeChat(existing: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  const map = new Map(existing.map((m) => [m.id, m]));
  for (const m of incoming) map.set(m.id, m);
  return [...map.values()].sort((a, b) => new Date(a.sentAt).getTime() - new Date(b.sentAt).getTime());
}

/**
 * join-status errors that will not go away by polling again: the meeting is over, the participant no longer
 * exists / is not allowed. (Network errors, 429 and 5xx keep polling.)
 */
export function isTerminalJoinStatusError(e: unknown): boolean {
  if (!(e instanceof ApiError)) return false;
  return e.status === 401 || e.status === 403 || e.status === 404 || e.status === 409 || e.status === 410;
}
