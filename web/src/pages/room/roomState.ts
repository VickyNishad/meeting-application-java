import type { JoinResponse, MediaPermissions, Meeting, MeetingRole, ParticipantMedia, RoomStateView, ScreenSharePolicy, VisibilityMode } from '@/lib/api/types';
import { TOPIC, isModeratorRole, parseModeration, parseRecording } from './roomData';

/**
 * The live room as this client knows it: one store, loaded from the server's snapshot (GET room-state) and kept
 * current by server signals. Signals to everyone carry `stream: "all"` and a sequence number, signals to the stage
 * `stream: "stage"`; a skipped number means a signal was missed, so the store is marked `stale` and the snapshot is
 * reloaded. Signals that arrive before the first snapshot are held and applied on top of it.
 */
export interface RoomState {
  loaded: boolean;
  /** The server counters' epoch: a signal from another epoch means the counters were reset, so reload. */
  epoch: string | null;
  seq: number;
  /** null when this participant is not on the stage (and receives no stage stream). */
  stageSeq: number | null;
  role: MeetingRole;
  visibilityMode: VisibilityMode;
  stageIdentities: string[];
  locked: boolean;
  chatEnabled: boolean;
  screenSharePolicy: ScreenSharePolicy;
  waitingRoomEnabled: boolean;
  cloudRecording: boolean;
  cloudRecordingAvailable: boolean;
  /** participantId → display name. */
  localRecorders: Record<string, string>;
  /** participantId → display name. */
  raisedHands: Record<string, string>;
  /** Roles changed during the session, by participant identity. */
  roleOverrides: Record<string, MeetingRole>;
  waitingCount: number;
  /** People in the session, counted by the server (one per person, reconnects and extra tabs included once). */
  presentCount: number;
  /** Meeting-wide media permissions for participants (hosts and co-hosts always may publish). */
  mediaPermissions: MediaPermissions;
  /** Participants' media rights by participant id: everyone's for the host, otherwise only one's own. */
  participantMedia: Record<string, ParticipantMedia>;
  /** Real names by identity (stage of a webinar, where the SFU shows attendees as "Attendee"). */
  names: Record<string, string>;
  stale: boolean;
  pending: Signal[];
}

export interface Signal {
  topic: string;
  raw: Record<string, unknown>;
}

export type RoomAction =
  | { type: 'snapshot'; view: RoomStateView }
  | { type: 'signal'; topic: string; raw: Record<string, unknown> | null; selfId: string }
  /** Own hand, applied at once (the server's echo confirms it). */
  | { type: 'own-hand'; participantId: string; name: string; raised: boolean }
  /** A moderator's own change, shown at once; the server's signal or the next snapshot confirms or corrects it. */
  | { type: 'patch'; patch: Partial<Pick<RoomState, 'locked' | 'screenSharePolicy' | 'waitingRoomEnabled' | 'chatEnabled' | 'cloudRecording' | 'mediaPermissions'>> }
  /** The host's own change of one participant's media rights (the server's answer). */
  | { type: 'participant-media'; media: ParticipantMedia };

const MAX_PENDING = 200;

/** {@code myName}: this participant's own name, for a webinar attendee whom the media server shows as "Attendee". */
export function initialRoomState(meeting: Meeting, join: JoinResponse, myName?: string): RoomState {
  return {
    loaded: false,
    epoch: null,
    seq: 0,
    stageSeq: null,
    role: join.role,
    visibilityMode: meeting.visibilityMode ?? 'EVERYONE_VISIBLE',
    stageIdentities: [],
    locked: meeting.locked,
    chatEnabled: meeting.chatEnabled !== false,
    screenSharePolicy: meeting.screenSharePolicy,
    waitingRoomEnabled: meeting.waitingRoomEnabled,
    // Until the snapshot: everyone may use microphone and camera, screen sharing as the meeting says.
    mediaPermissions: {
      microphone: 'ALLOWED',
      camera: 'ALLOWED',
      screenShare: meeting.screenSharePolicy === 'HOSTS_ONLY' ? 'RESTRICTED' : 'ALLOWED',
    },
    participantMedia: {},
    // Known from the join response for everyone, guests included, until the snapshot arrives.
    cloudRecording: !!join.meeting.recordingInProgress,
    // Offered only once the server has said so, so the control never flashes on a server without a recorder.
    cloudRecordingAvailable: false,
    localRecorders: {},
    raisedHands: {},
    roleOverrides: {},
    waitingCount: 0,
    presentCount: 0,
    names: myName ? { [join.participantId]: myName } : {},
    stale: false,
    pending: [],
  };
}

function byId(actors: { participantId: string; displayName: string }[]): Record<string, string> {
  return Object.fromEntries(actors.map((a) => [a.participantId, a.displayName]));
}

function fromSnapshot(state: RoomState, v: RoomStateView): RoomState {
  return {
    ...state,
    loaded: true,
    stale: false,
    pending: [],
    epoch: v.epoch ?? null,
    seq: v.seq,
    stageSeq: v.stageSeq ?? null,
    role: v.role,
    visibilityMode: v.visibilityMode,
    stageIdentities: v.stageIdentities,
    locked: v.locked,
    chatEnabled: v.chatEnabled,
    screenSharePolicy: v.screenSharePolicy,
    waitingRoomEnabled: v.waitingRoomEnabled,
    cloudRecording: v.cloudRecording,
    cloudRecordingAvailable: v.cloudRecordingAvailable ?? false,
    localRecorders: byId(v.localRecorders),
    raisedHands: byId(v.raisedHands),
    // The snapshot's roles are current: earlier overrides are folded into stageIdentities/role.
    roleOverrides: {},
    waitingCount: v.waitingCount ?? state.waitingCount,
    presentCount: v.presentCount ?? state.presentCount,
    names: v.participants ? byId(v.participants) : state.names,
    mediaPermissions: v.mediaPermissions ?? state.mediaPermissions,
    participantMedia: Object.fromEntries([...(v.participantMedia ?? []), ...(v.myMedia ? [v.myMedia] : [])].map((m) => [m.participantId, m])),
  };
}

/** What a signal says about the room, independent of numbering. */
function applyContent(state: RoomState, topic: string, raw: Record<string, unknown>, selfId: string): RoomState {
  switch (topic) {
    case TOPIC.moderation: {
      const p = parseModeration(raw);
      if (!p) return state;
      switch (p.type) {
        case 'room-locked':
          return { ...state, locked: p.locked };
        case 'settings':
          return {
            ...state,
            screenSharePolicy: p.screenSharePolicy ?? state.screenSharePolicy,
            chatEnabled: p.chatEnabled ?? state.chatEnabled,
            waitingRoomEnabled: p.waitingRoomEnabled ?? state.waitingRoomEnabled,
          };
        case 'waiting-room':
          return { ...state, waitingCount: p.count };
        case 'media-permissions':
          return {
            ...state,
            mediaPermissions: { microphone: p.microphone, camera: p.camera, screenShare: p.screenShare },
            // The same setting as the screen-sharing policy.
            screenSharePolicy: p.screenShare === 'ALLOWED' ? 'EVERYONE' : 'HOSTS_ONLY',
          };
        case 'participant-media':
          return {
            ...state,
            participantMedia: { ...state.participantMedia, ...Object.fromEntries(p.participants.map((m) => [m.participantId, m])) },
          };
        case 'role-changed': {
          const onStage = isModeratorRole(p.role);
          const stageIdentities = onStage
            ? [...new Set([...state.stageIdentities, p.participantId])]
            : state.stageIdentities.filter((id) => id !== p.participantId);
          const next = { ...state, roleOverrides: { ...state.roleOverrides, [p.participantId]: p.role }, stageIdentities };
          if (p.participantId !== selfId) return next;
          // Joining or leaving the stage changes what this client may see (stage stream, real names): reload.
          return { ...next, role: p.role, stale: next.stale || isModeratorRole(p.role) !== isModeratorRole(state.role) };
        }
        default:
          return state;
      }
    }
    case TOPIC.recording: {
      const p = parseRecording(raw);
      if (!p) return state;
      if (p.kind === 'CLOUD') return { ...state, cloudRecording: p.state === 'STARTED' };
      if (p.kind === 'LOCAL' && p.participantId) {
        const localRecorders = { ...state.localRecorders };
        if (p.state === 'STARTED') localRecorders[p.participantId] = p.displayName ?? 'A participant';
        else delete localRecorders[p.participantId];
        return { ...state, localRecorders };
      }
      return state;
    }
    case TOPIC.presence: {
      if (raw.type !== 'presence' || typeof raw.count !== 'number' || raw.count < 0) return state;
      return { ...state, presentCount: raw.count };
    }
    case TOPIC.hand: {
      if (raw.type !== 'hand' || typeof raw.participantId !== 'string') return state;
      const raisedHands = { ...state.raisedHands };
      if (raw.raised === true) raisedHands[raw.participantId] = typeof raw.displayName === 'string' ? raw.displayName : 'A participant';
      else delete raisedHands[raw.participantId];
      return { ...state, raisedHands };
    }
    default:
      return state;
  }
}

function numbered(raw: Record<string, unknown>): { stream: 'all' | 'stage'; seq: number; epoch: string | null } | null {
  if ((raw.stream === 'all' || raw.stream === 'stage') && typeof raw.seq === 'number') {
    return { stream: raw.stream, seq: raw.seq, epoch: typeof raw.epoch === 'string' ? raw.epoch : null };
  }
  return null;
}

function applySignal(state: RoomState, topic: string, raw: Record<string, unknown>, selfId: string): RoomState {
  const n = numbered(raw);
  if (!n) return applyContent(state, topic, raw, selfId);
  if (n.stream === 'stage' && state.stageSeq === null) {
    // Not on the stage: this is our own action echoed back (e.g. an attendee's hand). Nothing to number.
    return applyContent(state, topic, raw, selfId);
  }
  if (n.epoch !== state.epoch) {
    // The server's counters were reset (or we have none yet): the numbers cannot be compared. Apply and reload.
    return { ...applyContent(state, topic, raw, selfId), stale: true };
  }
  const last = n.stream === 'all' ? state.seq : (state.stageSeq as number);
  if (n.seq <= last) return state; // already reflected (duplicate, or older than the snapshot)
  const next = applyContent(state, topic, raw, selfId);
  const gap = n.seq > last + 1;
  return n.stream === 'all'
    ? { ...next, seq: n.seq, stale: next.stale || gap }
    : { ...next, stageSeq: n.seq, stale: next.stale || gap };
}

export function roomReducer(state: RoomState, action: RoomAction): RoomState {
  switch (action.type) {
    case 'snapshot': {
      // A response that is older than what this store already shows (a slow request overtaken by newer signals,
      // with no reload pending) is dropped instead of overwriting newer state.
      // Both streams count: in HOST_AND_SELF_ONLY the stage learns about attendees (presence, hands) on the stage
      // stream only, so a snapshot can be current on `all` and still older on `stage` (QA56-7).
      const v = action.view;
      const olderOnStage = v.stageSeq != null && state.stageSeq != null && v.stageSeq < state.stageSeq;
      if (state.loaded && !state.stale && (v.epoch ?? null) === state.epoch && (v.seq < state.seq || olderOnStage)) {
        return state;
      }
      // Signals received while (re)loading are replayed on top: those the snapshot already covers are skipped by
      // number, the newer ones are applied, so an older snapshot never undoes a newer signal.
      let next = fromSnapshot(state, action.view);
      const held = [...state.pending].sort((a, b) => Number(a.raw.seq) - Number(b.raw.seq));
      for (const s of held) next = applySignal(next, s.topic, s.raw, action.view.participantId);
      return next;
    }
    case 'signal': {
      if (!action.raw) return state;
      if (numbered(action.raw)) {
        if (!state.loaded) {
          return { ...state, pending: [...state.pending, { topic: action.topic, raw: action.raw }].slice(-MAX_PENDING) };
        }
        if (state.stale) {
          // A reload is on its way: show the change now and keep it to replay on the new snapshot.
          const shown = applySignal(state, action.topic, action.raw, action.selfId);
          return { ...shown, pending: [...state.pending, { topic: action.topic, raw: action.raw }].slice(-MAX_PENDING) };
        }
      }
      return applySignal(state, action.topic, action.raw, action.selfId);
    }
    case 'own-hand': {
      const raisedHands = { ...state.raisedHands };
      if (action.raised) raisedHands[action.participantId] = action.name;
      else delete raisedHands[action.participantId];
      return { ...state, raisedHands };
    }
    case 'patch': {
      const next = { ...state, ...action.patch };
      // The screen-sharing permission and policy are one setting.
      if (action.patch.mediaPermissions) next.screenSharePolicy = action.patch.mediaPermissions.screenShare === 'ALLOWED' ? 'EVERYONE' : 'HOSTS_ONLY';
      if (action.patch.screenSharePolicy) {
        next.mediaPermissions = { ...next.mediaPermissions, screenShare: action.patch.screenSharePolicy === 'EVERYONE' ? 'ALLOWED' : 'RESTRICTED' };
      }
      return next;
    }
    case 'participant-media':
      return { ...state, participantMedia: { ...state.participantMedia, [action.media.participantId]: action.media } };
  }
}

/**
 * Whether a participant is on the stage (host or co-host): a role change seen this session, else the role in the
 * participant's metadata (set only by the server, current for people who joined after our snapshot), else the
 * snapshot's stage list.
 */
export function isOnStage(
  p: { identity: string; metadata?: string },
  roleOf: (metadata: string | undefined) => MeetingRole | undefined,
  overrides: Record<string, MeetingRole>,
  stageIdentities: string[] = [],
): boolean {
  const role = overrides[p.identity] ?? roleOf(p.metadata);
  return role ? isModeratorRole(role) : stageIdentities.includes(p.identity);
}

/** Identities of the stage among `participants` (see {@link isOnStage}). */
export function stageFromMetadata(
  participants: { identity: string; metadata?: string }[],
  roleOf: (metadata: string | undefined) => MeetingRole | undefined,
  overrides: Record<string, MeetingRole>,
): string[] {
  return participants.filter((p) => isOnStage(p, roleOf, overrides)).map((p) => p.identity);
}
