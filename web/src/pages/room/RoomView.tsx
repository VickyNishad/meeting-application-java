import { memo, useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react';
import {
  CarouselLayout,
  ConnectionStateToast,
  TrackRefContext,
  FocusLayoutContainer,
  GridLayout,
  ParticipantTile,
  RoomAudioRenderer,
  RoomContext,
  useConnectionQualityIndicator,
  useConnectionState,
  useDataChannel,
  useLocalParticipant,
  useMaybeTrackRefContext,
  useParticipants,
  useRoomContext,
  useSpeakingParticipants,
  useTracks,
  type TrackReferenceOrPlaceholder,
} from '@livekit/components-react';
import { ConnectionQuality, ConnectionState, RoomEvent, Track, type Participant, type Room, type RoomOptions } from 'livekit-client';
import { AlertTriangle, Circle, Eye, LayoutGrid, Loader2, Lock, Maximize, Minimize, PanelRightClose, Signal, SignalLow, SignalMedium, Users, WifiOff } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/primitives';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/overlays';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { moderationApi, roomStateApi } from '@/lib/api/endpoints';
import type { JoinResponse, Meeting } from '@/lib/api/types';
import { errorMessage } from '@/lib/api/problem';
import type { MediaState } from '@/lib/joinStore';
import { cn } from '@/lib/utils';
import { MeetingRoomContext, useMeetingRoom, type EndReason, type MeetingRoomState } from './roomContext';
import {
  REACTION_EMOJI,
  TOPIC,
  decodePacket,
  isFromServer,
  isModeratorRole,
  parseModeration,
  parseRecording,
  recordingNotice,
  roleFromMetadata,
} from './roomData';
import { initialRoomState, isOnStage, roomReducer } from './roomState';
import { AudienceRoomsContext, StageRoomContext, useAudienceRooms } from './useAudienceRoom';
import { useSpeakerReporter } from './useSpeakerReporter';
import { useSpeechRecorder } from './useSpeechRecorder';
import { useClearSpeakingOnReconnect, useSpeakingIndicator } from './speaking';
import { ControlBar } from './ControlBar';
import { ChatPanel } from './ChatPanel';
import { ParticipantsPanel } from './ParticipantsPanel';
import { ModerationPanel } from './ModerationPanel';
import { useMeetingChat } from './useMeetingChat';

/** The name the media server shows for webinar attendees (the stage gets real names from the room state). */
export const ATTENDEE_NAME = 'Attendee';

export type SidePanel = 'chat' | 'participants' | 'moderation' | null;
export type LayoutMode = 'grid' | 'speaker';

interface FloatingReaction {
  id: number;
  emoji: string;
  name: string;
  left: number;
}

function QualityIndicator() {
  const { localParticipant } = useLocalParticipant();
  const { quality } = useConnectionQualityIndicator({ participant: localParticipant });
  const map: Record<ConnectionQuality, { icon: typeof Signal; label: string; cls: string }> = {
    [ConnectionQuality.Excellent]: { icon: Signal, label: 'Excellent', cls: 'text-success' },
    [ConnectionQuality.Good]: { icon: SignalMedium, label: 'Good', cls: 'text-success' },
    [ConnectionQuality.Poor]: { icon: SignalLow, label: 'Poor', cls: 'text-warning' },
    [ConnectionQuality.Lost]: { icon: WifiOff, label: 'Lost', cls: 'text-destructive' },
    [ConnectionQuality.Unknown]: { icon: Signal, label: 'Unknown', cls: 'text-neutral-400' },
  };
  const q = map[quality] ?? map[ConnectionQuality.Unknown];
  return (
    <span className={cn('inline-flex items-center gap-1 text-xs', q.cls)} title={`Connection quality: ${q.label}`}>
      <q.icon className="size-4" aria-hidden="true" />
      <span className="sr-only sm:not-sr-only">{q.label}</span>
    </span>
  );
}

function RecordingIndicator({ myLocalRecording }: { myLocalRecording: boolean }) {
  const { meeting, cloudRecording, localRecorders, join, stageOnly } = useMeetingRoom();
  const others = Object.entries(localRecorders).filter(([pid]) => pid !== join.participantId);
  // Auto-record is announced for the whole meeting, whether or not the recorder has started yet.
  if (!meeting.autoRecord && !cloudRecording && others.length === 0 && !myLocalRecording) return null;
  const parts: string[] = [];
  const subject = stageOnly ? 'the host and co-hosts are recorded' : 'the recording follows whoever is speaking';
  if (meeting.autoRecord) parts.push(`This meeting is recorded automatically (${subject})`);
  else if (cloudRecording) parts.push('This meeting is being recorded (cloud)');
  if (myLocalRecording) parts.push('You are recording locally');
  for (const [, name] of others) parts.push(`${name} is recording locally`);
  const text = parts.join(' · ');
  return (
    <div role="status" aria-live="polite" className="flex items-center gap-2 rounded-full bg-destructive/90 px-3 py-1 text-xs font-semibold text-white" title={text}>
      <Circle className="size-2.5 animate-pulse fill-current" aria-hidden="true" />
      REC
      <span className="hidden max-w-[40vw] truncate font-normal md:inline">{text}</span>
      <span className="sr-only md:hidden">{text}</span>
    </div>
  );
}

/** How long someone must keep speaking before the speaker view switches to them (avoids flicker on short sounds). */
export const SPEAKER_HOLD_MS = 1500;

/**
 * The speaker view's focus: the first remote speaker, but only after they have been the top speaker for
 * SPEAKER_HOLD_MS; the previous speaker stays in focus meanwhile and when nobody speaks.
 */
function useHeldSpeaker(allowed: (p: Visible) => boolean, extraSpeakers: Participant[]): string | null {
  const stageSpeakers = useSpeakingParticipants();
  const speaking = useMemo(() => [...stageSpeakers, ...extraSpeakers], [stageSpeakers, extraSpeakers]);
  const [held, setHeld] = useState<string | null>(null);
  const candidate = (speaking.find((p) => !p.isLocal && allowed(p)) ?? speaking.find((p) => allowed(p)))?.identity ?? null;
  useEffect(() => {
    if (!candidate || candidate === held) return;
    // The first speaker is shown at once; later changes wait for the hold time.
    if (held === null) {
      setHeld(candidate);
      return;
    }
    const t = window.setTimeout(() => setHeld(candidate), SPEAKER_HOLD_MS);
    return () => window.clearTimeout(t);
  }, [candidate, held]);
  return held;
}

/**
 * A tile labelled with the participant's real name where the media server only shows "Attendee", framed in green
 * while that participant is speaking (see speaking.ts; every speaker is highlighted, not only the loudest).
 * Memoised: when the grid or carousel re-renders (pages re-sorted, focus moved), tiles whose track did not change
 * are not rendered again; each tile re-renders for its own speaking changes only.
 */
const NamedTile = memo(function NamedTile() {
  const trackRef = useMaybeTrackRefContext();
  const { names } = useMeetingRoom();
  const real = trackRef && trackRef.participant.name === ATTENDEE_NAME ? names[trackRef.participant.identity] : undefined;
  // A screen share is not a person: it is never framed.
  const speaking = useSpeakingIndicator(trackRef?.source === Track.Source.ScreenShare ? undefined : trackRef?.participant);
  return (
    <div className="speaking-tile relative h-full w-full" data-speaking={speaking ? 'true' : undefined}>
      <ParticipantTile />
      <span aria-hidden="true" className={cn('speaking-frame', speaking && 'is-speaking')} />
      {real && (
        <span className="pointer-events-none absolute left-2 top-2 z-10 rounded bg-black/70 px-1.5 py-0.5 text-xs text-white">{real}</span>
      )}
    </div>
  );
});

type Visible = { identity: string; metadata?: string; isLocal: boolean };

/** Webinar attendees see the stage and themselves; everyone else sees everyone. */
function useVisibleIdentity(): (p: Visible) => boolean {
  const { stageOnly, isModerator, stageIdentities, roleOverrides } = useMeetingRoom();
  return useCallback(
    (p: Visible) => !stageOnly || isModerator || p.isLocal || isOnStage(p, roleFromMetadata, roleOverrides, stageIdentities),
    [stageOnly, isModerator, stageIdentities, roleOverrides],
  );
}

/**
 * Tiles from the audience rooms of a HOST_AND_SELF_ONLY session: for the stage every attendee (the stage subscribes
 * in each attendee's room), for an attendee only their own preview (attendees cannot subscribe there), once its
 * room is connected.
 */
function useAudienceTiles(rooms: Room[], ownOnly: boolean): TrackReferenceOrPlaceholder[] {
  const [tiles, setTiles] = useState<TrackReferenceOrPlaceholder[]>([]);
  useEffect(() => {
    if (rooms.length === 0) {
      setTiles([]);
      return;
    }
    const update = () => {
      const people: Participant[] = ownOnly
        ? rooms.slice(0, 1).filter((room) => room.state === ConnectionState.Connected).map((room) => room.localParticipant)
        : rooms.flatMap((room) => [...room.remoteParticipants.values()]);
      const next: TrackReferenceOrPlaceholder[] = [];
      for (const participant of people) {
        const camera = participant.getTrackPublication(Track.Source.Camera);
        next.push(camera ? { participant, source: Track.Source.Camera, publication: camera } : { participant, source: Track.Source.Camera });
        const screen = participant.getTrackPublication(Track.Source.ScreenShare);
        if (screen) next.push({ participant, source: Track.Source.ScreenShare, publication: screen });
      }
      setTiles(next);
    };
    update();
    const events = [RoomEvent.Connected, RoomEvent.ConnectionStateChanged, RoomEvent.ParticipantConnected, RoomEvent.ParticipantDisconnected,
      RoomEvent.TrackPublished, RoomEvent.TrackUnpublished, RoomEvent.TrackSubscribed, RoomEvent.TrackUnsubscribed,
      RoomEvent.LocalTrackPublished, RoomEvent.LocalTrackUnpublished, RoomEvent.TrackMuted, RoomEvent.TrackUnmuted] as const;
    for (const room of rooms) for (const e of events) room.on(e, update);
    return () => {
      for (const room of rooms) for (const e of events) room.off(e, update);
    };
  }, [rooms, ownOnly]);
  return tiles;
}

/** Who is speaking in some rooms (the SFU's voice-activity detection, not "microphone on"). */
function useRoomSpeakers(rooms: Room[]): Participant[] {
  const [speakers, setSpeakers] = useState<Participant[]>([]);
  useEffect(() => {
    const byRoom = new Map<Room, Participant[]>();
    const publish = () => setSpeakers([...byRoom.values()].flat());
    const offs = rooms.map((room) => {
      const update = (list: Participant[]) => {
        byRoom.set(room, list);
        publish();
      };
      // A dropped connection reports no speakers: nobody there is shown speaking until it is back.
      const clear = () => {
        byRoom.delete(room);
        publish();
      };
      room.on(RoomEvent.ActiveSpeakersChanged, update);
      room.on(RoomEvent.Reconnecting, clear);
      room.on(RoomEvent.Disconnected, clear);
      return () => {
        room.off(RoomEvent.ActiveSpeakersChanged, update);
        room.off(RoomEvent.Reconnecting, clear);
        room.off(RoomEvent.Disconnected, clear);
      };
    });
    publish();
    return () => offs.forEach((off) => off());
  }, [rooms]);
  return speakers;
}

const NO_ROOMS: Room[] = [];

/**
 * The tiles. Speaking changes do not re-render it: each tile follows its own participant (NamedTile). Only the
 * layouts that move tiles to whoever speaks listen to the speaker lists: the grid (LiveKit moves a speaker on another
 * page onto the visible one) and the speaker view (focus).
 */
function Stage({ layout, audienceRooms }: { layout: LayoutMode; audienceRooms: Room[] }) {
  const { isModerator, stageOnly } = useMeetingRoom();
  const room = useRoomContext();
  // Every room whose participants have tiles here: the stage room and the audience rooms (own, or every attendee's).
  const speakerRooms = useMemo(() => [room, ...audienceRooms], [room, audienceRooms]);
  useClearSpeakingOnReconnect(speakerRooms);
  const stageTracks = useTracks(
    [
      { source: Track.Source.Camera, withPlaceholder: true },
      { source: Track.Source.ScreenShare, withPlaceholder: false },
    ],
    { updateOnlyOn: [RoomEvent.ParticipantMetadataChanged], onlySubscribed: false },
  );
  const audienceTiles = useAudienceTiles(audienceRooms, !isModerator);
  const visible = useVisibleIdentity();
  // A HOST_AND_SELF_ONLY attendee is in the stage room only to watch (hidden, publishing nothing): its tile there is
  // an empty placeholder. Once its own audience room is connected, that room's tile (camera, speaking) is its one tile
  // (useAudienceTiles lists it only while connected; until then, and if that room drops, the placeholder shows).
  const selfInAudienceRoom = stageOnly && !isModerator && audienceTiles.length > 0;
  const tracks = useMemo(
    () =>
      [...(selfInAudienceRoom ? stageTracks.filter((t) => !t.participant.isLocal) : stageTracks), ...audienceTiles].filter((t) =>
        visible(t.participant),
      ),
    [stageTracks, audienceTiles, visible, selfInAudienceRoom],
  );
  const screenShare = tracks.find((t) => t.source === Track.Source.ScreenShare && t.publication);

  return (
    <div className="h-full">
      {layout === 'grid' && !screenShare ? (
        <GridStage tracks={tracks} speakerRooms={speakerRooms} />
      ) : (
        <SpeakerStage tracks={tracks} screenShare={screenShare} audienceRooms={isModerator ? audienceRooms : NO_ROOMS} visible={visible} />
      )}
    </div>
  );
}

/**
 * The gallery. LiveKit's grid keeps tiles in place but moves someone who starts speaking on another page onto the
 * visible one; it does that when it renders, so it renders again whenever the speakers change (tiles are memoised).
 */
export function GridStage({ tracks, speakerRooms }: { tracks: TrackReferenceOrPlaceholder[]; speakerRooms: Room[] }) {
  useRoomSpeakers(speakerRooms);
  return (
    <GridLayout tracks={tracks} className="h-full">
      <NamedTile />
    </GridLayout>
  );
}

function SpeakerStage({
  tracks,
  screenShare,
  audienceRooms,
  visible,
}: {
  tracks: TrackReferenceOrPlaceholder[];
  screenShare: TrackReferenceOrPlaceholder | undefined;
  audienceRooms: Room[];
  visible: (p: Visible) => boolean;
}) {
  const audienceSpeakers = useRoomSpeakers(audienceRooms);
  const lastSpeaker = useHeldSpeaker(visible, audienceSpeakers);
  const focus =
    screenShare ??
    tracks.find((t) => t.source === Track.Source.Camera && t.participant.identity === lastSpeaker) ??
    tracks.find((t) => t.source === Track.Source.Camera && !t.participant.isLocal) ??
    tracks[0];
  const others = tracks.filter((t) => t !== focus);
  return (
    <FocusLayoutContainer className="h-full">
      {others.length > 0 && (
        <CarouselLayout tracks={others}>
          <NamedTile />
        </CarouselLayout>
      )}
      {focus && (
        <TrackRefContext.Provider value={focus}>
          {/* Keyed: a new person in focus gets a fresh tile, never the previous one's speaking state. */}
          <NamedTile key={`${focus.participant.identity}:${focus.source}`} />
        </TrackRefContext.Provider>
      )}
    </FocusLayoutContainer>
  );
}

/**
 * The number of people in the session, from the server (authoritative membership, one per person even with several
 * reconnects or tabs). Until the first room state arrives, the media connections' own view is shown.
 */
function ParticipantCount({ serverCount }: { serverCount: number | null }) {
  const participants = useParticipants({
    updateOnlyOn: [RoomEvent.ParticipantConnected, RoomEvent.ParticipantDisconnected, RoomEvent.ParticipantMetadataChanged],
  });
  const visible = useVisibleIdentity();
  const count = serverCount ?? participants.filter((p) => visible(p)).length;
  return (
    <span className="inline-flex items-center gap-1 text-xs text-neutral-300" aria-label={`${count} participants`}>
      <Users className="size-4" aria-hidden="true" /> {count}
    </span>
  );
}

function UnmuteRequestDialog({ request, onClose }: { request: 'MICROPHONE' | 'CAMERA' | null; onClose: () => void }) {
  const { localParticipant } = useLocalParticipant();
  const [busy, setBusy] = useState(false);
  const accept = async () => {
    setBusy(true);
    try {
      // Only the participant's explicit click re-enables the device — never automatic.
      if (request === 'MICROPHONE') await localParticipant.setMicrophoneEnabled(true);
      else if (request === 'CAMERA') await localParticipant.setCameraEnabled(true);
      onClose();
    } catch (e) {
      toast.error(`Could not turn on your ${request === 'CAMERA' ? 'camera' : 'microphone'}: ${errorMessage(e)}`);
    } finally {
      setBusy(false);
    }
  };
  const what = request === 'CAMERA' ? 'turn on your camera' : 'unmute your microphone';
  return (
    <Dialog open={request !== null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>The host is asking you to {what}</DialogTitle>
          <DialogDescription>Nothing changes unless you choose to. You can stay {request === 'CAMERA' ? 'off camera' : 'muted'}.</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {request === 'CAMERA' ? 'Keep camera off' : 'Stay muted'}
          </Button>
          <Button onClick={() => void accept()} loading={busy}>
            {request === 'CAMERA' ? 'Turn on camera' : 'Unmute'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ReconnectBanner({ error, onRetry }: { error: Error | null; onRetry: () => void }) {
  const state = useConnectionState();
  if (error) {
    return (
      <div role="alert" className="flex flex-wrap items-center justify-center gap-3 bg-destructive px-4 py-2 text-sm text-white">
        <AlertTriangle className="size-4" aria-hidden="true" />
        {error.message}
        <Button size="sm" variant="secondary" onClick={onRetry}>
          Reconnect
        </Button>
      </div>
    );
  }
  if (state === ConnectionState.Reconnecting || state === ConnectionState.SignalReconnecting) {
    return (
      <div role="status" aria-live="assertive" className="flex items-center justify-center gap-2 bg-warning px-4 py-2 text-sm font-medium text-black">
        <Loader2 className="size-4 animate-spin" aria-hidden="true" /> Connection lost — reconnecting…
      </div>
    );
  }
  if (state === ConnectionState.Connecting) {
    return (
      <div role="status" className="flex items-center justify-center gap-2 bg-neutral-800 px-4 py-2 text-sm text-neutral-200">
        <Loader2 className="size-4 animate-spin" aria-hidden="true" /> Connecting…
      </div>
    );
  }
  return null;
}

export function RoomView({
  meeting,
  join,
  finish,
  connectError,
  onRetry,
  onMediaChange,
  audienceMedia = null,
  roomOptions = {},
}: {
  meeting: Meeting;
  join: JoinResponse;
  finish: (r: EndReason) => void;
  connectError: Error | null;
  /** User-initiated reconnect: fetches fresh media tokens. Also used after this participant's role changes. */
  onRetry: () => void;
  /** Called with the user's mic/camera state whenever it changes while connected (persisted for reconnects). */
  onMediaChange?: (m: MediaState) => void;
  /** HOST_AND_SELF_ONLY attendee: devices to publish in the audience room (nothing is published in the stage room). */
  audienceMedia?: MediaState | null;
  /** Capture and playback options for the audience room connection (same as the stage room's). */
  roomOptions?: RoomOptions;
}) {
  const room = useRoomContext();
  const containerRef = useRef<HTMLDivElement>(null);
  const [state, dispatch] = useReducer(roomReducer, undefined, () => initialRoomState(meeting, join));
  const [myLocalRecording, setMyLocalRecording] = useState(false);
  const [panel, setPanel] = useState<SidePanel>(null);
  const [layout, setLayout] = useState<LayoutMode>('grid');
  const [fullscreen, setFullscreen] = useState(false);
  const [unmuteRequest, setUnmuteRequest] = useState<'MICROPHONE' | 'CAMERA' | null>(null);
  const [reactions, setReactions] = useState<FloatingReaction[]>([]);
  const [unreadChat, setUnreadChat] = useState(0);
  const reactionId = useRef(0);
  const myRole = state.role;
  const isModerator = isModeratorRole(myRole);
  const stageOnly = state.visibilityMode === 'HOST_AND_SELF_ONLY';
  const queryClient = useQueryClient();

  // The room as the server knows it: loaded once connected, after reconnects, and whenever a signal was missed.
  // Keyed by the media token and never cached across mounts, so a new connection never starts from an old view
  // (e.g. a role that changed while disconnected).
  const connection = useConnectionState();
  const [everConnected, setEverConnected] = useState(false);
  useEffect(() => {
    if (connection === ConnectionState.Connected) setEverConnected(true);
  }, [connection]);
  const snapshot = useQuery({
    queryKey: ['room-state', meeting.id, join.participantId, join.token],
    queryFn: () => roomStateApi.get(meeting.id, join.participantId, join.waitToken),
    enabled: everConnected,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    retry: 2,
  });
  const refetchSnapshot = snapshot.refetch;
  useEffect(() => {
    if (snapshot.data) dispatch({ type: 'snapshot', view: snapshot.data });
  }, [snapshot.data, snapshot.dataUpdatedAt]);
  useEffect(() => {
    if (!state.stale) return;
    // Small random delay so a whole room that missed the same signal does not reload at the same instant.
    const t = window.setTimeout(() => void refetchSnapshot(), 200 + Math.random() * 800);
    return () => window.clearTimeout(t);
  }, [state.stale, refetchSnapshot]);
  // A failed (re)load is retried with backoff for as long as the view is missing or known to be out of date.
  const failures = useRef(0);
  useEffect(() => {
    if (snapshot.isSuccess) failures.current = 0;
  }, [snapshot.isSuccess, snapshot.dataUpdatedAt]);
  useEffect(() => {
    if (!snapshot.isError || !(state.stale || !state.loaded)) return;
    failures.current += 1;
    const delay = Math.min(30_000, 2000 * 2 ** Math.min(failures.current - 1, 4));
    const t = window.setTimeout(() => void refetchSnapshot(), delay);
    return () => window.clearTimeout(t);
  }, [snapshot.isError, snapshot.errorUpdatedAt, state.stale, state.loaded, refetchSnapshot]);
  useEffect(() => {
    const reload = () => void refetchSnapshot();
    room.on(RoomEvent.Reconnected, reload);
    return () => {
      room.off(RoomEvent.Reconnected, reload);
    };
  }, [room, refetchSnapshot]);
  // Stage of a webinar: a newcomer the media server shows as "Attendee" needs its real name from the server.
  const knownNames = state.names;
  useEffect(() => {
    if (!stageOnly || !isModerator) return;
    let t: number | undefined;
    const onJoin = (p: { identity: string; name?: string }) => {
      if (p.name !== ATTENDEE_NAME || knownNames[p.identity]) return;
      window.clearTimeout(t);
      t = window.setTimeout(() => void refetchSnapshot(), 1000);
    };
    room.on(RoomEvent.ParticipantConnected, onJoin);
    return () => {
      window.clearTimeout(t);
      room.off(RoomEvent.ParticipantConnected, onJoin);
    };
  }, [room, stageOnly, isModerator, knownNames, refetchSnapshot]);

  // HOST_AND_SELF_ONLY: the audience room connections (what may be done there is fixed by the server's tokens). An
  // attendee has its own room; the stage watches every attendee's, so it asks again when the count changes. A
  // connection that fails or drops is connected again with fresh tokens.
  const audienceAccess = useQuery({
    queryKey: ['audience-access', meeting.id, join.participantId, join.token],
    queryFn: () => roomStateApi.audienceAccess(meeting.id, join.participantId, join.waitToken),
    enabled: stageOnly && everConnected,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchInterval: isModerator ? 30_000 : false,
    retry: 2,
  });
  const refetchAudience = audienceAccess.refetch;
  const presentCount = state.presentCount;
  useEffect(() => {
    if (stageOnly && isModerator && presentCount !== undefined) void refetchAudience();
  }, [stageOnly, isModerator, presentCount, refetchAudience]);
  const lostTimer = useRef<number | undefined>(undefined);
  const onAudienceLost = useCallback(() => {
    if (lostTimer.current !== undefined) return;
    lostTimer.current = window.setTimeout(() => {
      lostTimer.current = undefined;
      void refetchAudience();
    }, 2000);
  }, [refetchAudience]);
  useEffect(() => () => window.clearTimeout(lostTimer.current), []);
  const onAudienceError = useCallback(
    (e: unknown) => {
      if (!isModerator) {
        toast.error(`Could not connect your camera and microphone to the meeting: ${errorMessage(e)}. Retrying…`, { id: 'audience-room' });
      }
    },
    [isModerator],
  );
  const audienceRooms = useAudienceRooms(
    audienceAccess.data?.livekitUrl ?? join.livekitUrl,
    stageOnly ? audienceAccess.data?.rooms : undefined,
    roomOptions,
    isModerator ? null : audienceMedia,
    onAudienceError,
    onAudienceLost,
  );
  const ownAudienceRoom = isModerator ? null : (audienceRooms[0] ?? null);
  // Speaking clips: this participant's own turns, in meetings with automatic recording (everyone was told on joining).
  const onClipError = useCallback(
    (e: unknown) => toast.error(`Your speaking clip could not be saved: ${errorMessage(e)}`, { id: 'speech-clip' }),
    [],
  );
  useSpeechRecorder({
    enabled: meeting.autoRecord,
    participant: (ownAudienceRoom ?? room).localParticipant ?? null,
    meetingId: meeting.id,
    participantId: join.participantId,
    waitToken: join.waitToken,
    onError: onClipError,
  });
  const speakerRooms = useMemo(() => [room, ...audienceRooms], [room, audienceRooms]);
  useSpeakerReporter(speakerRooms, meeting.id, join.participantId, isModerator);
  // Joining or leaving the stage changes which rooms and rights this participant has: reconnect with new tokens.
  const joinedAsStage = isModeratorRole(join.role);
  useEffect(() => {
    if (stageOnly && state.loaded && isModerator !== joinedAsStage) onRetry();
  }, [stageOnly, state.loaded, isModerator, joinedAsStage, onRetry]);

  // Hosts/co-hosts also poll the waiting room (shared cache with the moderation panel) as a backstop for a missed
  // `waiting-room` signal; only while the tab is visible.
  const waitingQuery = useQuery({
    queryKey: ['waiting', meeting.id],
    queryFn: () => moderationApi.waiting(meeting.id),
    enabled: isModerator,
    refetchInterval: 15_000,
    refetchIntervalInBackground: false,
  });
  const waitingCount = isModerator && waitingQuery.data ? waitingQuery.data.length : state.waitingCount;
  const prevWaiting = useRef(0);
  useEffect(() => {
    if (isModerator && waitingCount > prevWaiting.current) {
      toast.info(`${waitingCount} ${waitingCount === 1 ? 'person is' : 'people are'} waiting to join`, {
        id: 'waiting-room',
        action: { label: 'Review', onClick: () => setPanel('moderation') },
      });
    }
    prevWaiting.current = waitingCount;
  }, [waitingCount, isModerator]);
  const panelRef = useRef<SidePanel>(null);
  useEffect(() => {
    panelRef.current = panel;
  }, [panel]);

  const myName = room.localParticipant?.name || 'You';
  const setHand = useCallback(
    (identity: string, raised: boolean) => dispatch({ type: 'own-hand', participantId: identity, name: myName, raised }),
    [myName],
  );

  const showReaction = useCallback((emoji: string, name: string) => {
    const id = ++reactionId.current;
    setReactions((r) => [...r.slice(-20), { id, emoji, name, left: 10 + Math.random() * 70 }]);
    setTimeout(() => setReactions((r) => r.filter((x) => x.id !== id)), 3000);
  }, []);

  // Everything on these topics is sent by the server (participants cannot publish data).
  useDataChannel(TOPIC.moderation, (msg) => {
    if (!isFromServer(msg.from)) return;
    const raw = decodePacket(msg.payload);
    dispatch({ type: 'signal', topic: TOPIC.moderation, raw, selfId: join.participantId });
    const p = parseModeration(raw);
    if (!p) return;
    switch (p.type) {
      case 'request-unmute':
        setUnmuteRequest(p.source);
        break;
      case 'removed':
        void room.disconnect();
        finish('removed');
        break;
      case 'meeting-ended':
        void room.disconnect();
        finish('ended');
        break;
      case 'room-locked':
        toast.info(p.locked ? 'The meeting was locked — no one else can join.' : 'The meeting was unlocked.');
        break;
      case 'role-changed':
        if (p.participantId === join.participantId) {
          toast.info(p.role === 'CO_HOST' ? 'You are now a co-host.' : `Your role changed to ${p.role.toLowerCase().replace('_', '-')}.`);
        }
        break;
      case 'waiting-room':
        void queryClient.invalidateQueries({ queryKey: ['waiting', meeting.id] });
        break;
      case 'settings':
        if (p.chatEnabled !== undefined) toast.info(p.chatEnabled ? 'Chat was enabled by the host' : 'Chat was disabled by the host');
        break;
    }
  });

  useDataChannel(TOPIC.recording, (msg) => {
    if (!isFromServer(msg.from)) return;
    const raw = decodePacket(msg.payload);
    dispatch({ type: 'signal', topic: TOPIC.recording, raw, selfId: join.participantId });
    const p = parseRecording(raw);
    const notice = p && recordingNotice(p, join.participantId, meeting.autoRecord);
    if (notice) toast[notice.level](notice.text, { id: notice.id });
  });

  // Chat and file signals are handled by the chat panel; they are numbered too, so they are counted here (a
  // number the store did not see would look like a missed signal and make the whole room reload).
  useDataChannel(TOPIC.chat, (msg) => {
    if (isFromServer(msg.from)) dispatch({ type: 'signal', topic: TOPIC.chat, raw: decodePacket(msg.payload), selfId: join.participantId });
  });
  useDataChannel(TOPIC.presence, (msg) => {
    if (isFromServer(msg.from)) dispatch({ type: 'signal', topic: TOPIC.presence, raw: decodePacket(msg.payload), selfId: join.participantId });
  });
  useDataChannel(TOPIC.files, (msg) => {
    if (isFromServer(msg.from)) dispatch({ type: 'signal', topic: TOPIC.files, raw: decodePacket(msg.payload), selfId: join.participantId });
  });

  useDataChannel(TOPIC.reactions, (msg) => {
    if (!isFromServer(msg.from)) return;
    const raw = decodePacket(msg.payload);
    dispatch({ type: 'signal', topic: TOPIC.reactions, raw, selfId: join.participantId });
    const emoji = raw?.type === 'reaction' && typeof raw.emoji === 'string' ? raw.emoji : null;
    if (!emoji || !(REACTION_EMOJI as readonly string[]).includes(emoji)) return;
    // Our own reaction was already shown when we sent it.
    if (raw?.participantId === join.participantId) return;
    showReaction(emoji, typeof raw?.displayName === 'string' ? raw.displayName : 'Someone');
  });

  useDataChannel(TOPIC.hand, (msg) => {
    if (!isFromServer(msg.from)) return;
    const raw = decodePacket(msg.payload);
    dispatch({ type: 'signal', topic: TOPIC.hand, raw, selfId: join.participantId });
    if (raw?.type === 'hand' && raw.raised === true && raw.participantId !== join.participantId) {
      toast(`✋ ${typeof raw.displayName === 'string' ? raw.displayName : 'A participant'} raised their hand`);
    }
  });

  // Remember the latest mic/camera state (own toggles, host mutes, accepted unmute requests) so a reconnect or
  // refresh republishes exactly that. Only mute/unmute/publish events count: while disconnecting, LiveKit
  // unpublishes everything with the room still "connected", which must not read as "the user turned it off".
  useEffect(() => {
    if (!onMediaChange) return;
    // The room this participant publishes in: the audience room for a HOST_AND_SELF_ONLY attendee.
    const pub = ownAudienceRoom ?? room;
    const record = (_pub: unknown, participant?: { isLocal?: boolean }) => {
      if (participant && !participant.isLocal) return;
      if (pub.state !== ConnectionState.Connected) return;
      const lp = pub.localParticipant;
      onMediaChange({ audioEnabled: lp.isMicrophoneEnabled, videoEnabled: lp.isCameraEnabled });
    };
    pub.on(RoomEvent.LocalTrackPublished, record);
    pub.on(RoomEvent.TrackMuted, record);
    pub.on(RoomEvent.TrackUnmuted, record);
    return () => {
      pub.off(RoomEvent.LocalTrackPublished, record);
      pub.off(RoomEvent.TrackMuted, record);
      pub.off(RoomEvent.TrackUnmuted, record);
    };
  }, [room, ownAudienceRoom, onMediaChange]);

  useEffect(() => {
    const onFs = () => setFullscreen(document.fullscreenElement !== null);
    document.addEventListener('fullscreenchange', onFs);
    return () => document.removeEventListener('fullscreenchange', onFs);
  }, []);

  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else await containerRef.current?.requestFullscreen();
    } catch (e) {
      toast.error(`Full screen is not available: ${errorMessage(e)}`);
    }
  };

  const setters = useMemo(
    () => ({
      setLocked: (locked: boolean) => dispatch({ type: 'patch', patch: { locked } }),
      setScreenSharePolicy: (screenSharePolicy: MeetingRoomState['screenSharePolicy']) => dispatch({ type: 'patch', patch: { screenSharePolicy } }),
      setWaitingRoomEnabled: (waitingRoomEnabled: boolean) => dispatch({ type: 'patch', patch: { waitingRoomEnabled } }),
      setChatEnabled: (chatEnabled: boolean) => dispatch({ type: 'patch', patch: { chatEnabled } }),
      setCloudRecording: (cloudRecording: boolean) => dispatch({ type: 'patch', patch: { cloudRecording } }),
    }),
    [],
  );

  const ctx = useMemo<MeetingRoomState>(
    () => ({
      meeting,
      join,
      myRole,
      isModerator,
      stageOnly,
      stageIdentities: state.stageIdentities,
      locked: state.locked,
      screenSharePolicy: state.screenSharePolicy,
      waitingRoomEnabled: state.waitingRoomEnabled,
      chatEnabled: state.chatEnabled,
      roleOverrides: state.roleOverrides,
      raisedHands: state.raisedHands,
      setHand,
      waitingCount,
      cloudRecording: state.cloudRecording,
      localRecorders: state.localRecorders,
      names: state.names,
      presentCount: state.loaded ? state.presentCount : null,
      audienceRooms,
      finish,
      ...setters,
    }),
    // Field by field: a buffered or duplicate signal that changes nothing visible must not re-render the room.
    [meeting, join, myRole, isModerator, stageOnly, state.stageIdentities, state.locked, state.screenSharePolicy,
      state.waitingRoomEnabled, state.chatEnabled, state.roleOverrides, state.raisedHands, state.cloudRecording,
      state.localRecorders, state.names, state.loaded, state.presentCount, audienceRooms, setHand, waitingCount, finish,
      setters],
  );

  const togglePanel = (p: Exclude<SidePanel, null>) => {
    setPanel((cur) => (cur === p ? null : p));
    if (p === 'chat') setUnreadChat(0);
  };

  const onChatMessage = useCallback(() => {
    if (panelRef.current !== 'chat') setUnreadChat((n) => n + 1);
  }, []);
  // Chat state lives here so messages and unread counts survive closing the panel.
  const chat = useMeetingChat(meeting.id, join.participantId, join.waitToken, onChatMessage);

  return (
    <MeetingRoomContext.Provider value={ctx}>
      <div ref={containerRef} className="flex h-dvh flex-col bg-neutral-950 text-neutral-100">
        <ReconnectBanner error={connectError} onRetry={onRetry} />
        <header className="flex flex-wrap items-center gap-2 border-b border-white/10 px-3 py-2 sm:px-4">
          <h1 className="min-w-0 max-w-[45vw] truncate text-sm font-semibold sm:text-base">{meeting.title}</h1>
          {state.locked && (
            <Badge className="bg-white/10 text-neutral-100">
              <Lock className="size-3" aria-hidden="true" /> Locked
            </Badge>
          )}
          {stageOnly && (
            <Badge
              className="bg-white/10 text-neutral-100"
              title={isModerator ? 'Attendees see and hear only the host and co-hosts' : 'You see and hear the host and co-hosts; only they see and hear you'}
            >
              <Eye className="size-3" aria-hidden="true" /> Webinar
            </Badge>
          )}
          <RecordingIndicator myLocalRecording={myLocalRecording} />
          <div className="ml-auto flex items-center gap-1 sm:gap-2">
            <QualityIndicator />
            <ParticipantCount serverCount={state.loaded ? state.presentCount : null} />
            <Button
              variant="ghost"
              size="icon"
              className="text-neutral-100 hover:bg-white/10 hover:text-white"
              aria-label={layout === 'grid' ? 'Switch to active speaker view' : 'Switch to gallery view'}
              title={layout === 'grid' ? 'Active speaker view' : 'Gallery view'}
              onClick={() => setLayout((l) => (l === 'grid' ? 'speaker' : 'grid'))}
            >
              {layout === 'grid' ? <PanelRightClose aria-hidden="true" /> : <LayoutGrid aria-hidden="true" />}
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="text-neutral-100 hover:bg-white/10 hover:text-white"
              aria-label={fullscreen ? 'Exit full screen' : 'Enter full screen'}
              onClick={() => void toggleFullscreen()}
            >
              {fullscreen ? <Minimize aria-hidden="true" /> : <Maximize aria-hidden="true" />}
            </Button>
          </div>
        </header>

        <div className="relative flex min-h-0 flex-1">
          <div className="relative min-w-0 flex-1 p-2">
            <Stage layout={layout} audienceRooms={audienceRooms} />
            <div className="pointer-events-none absolute inset-x-0 bottom-0 h-full overflow-hidden" aria-hidden="true">
              {reactions.map((r) => (
                <div key={r.id} className="animate-float-up absolute bottom-4 flex flex-col items-center" style={{ left: `${r.left}%` }}>
                  <span className="text-4xl">{r.emoji}</span>
                  <span className="rounded bg-black/60 px-1.5 text-[10px]">{r.name}</span>
                </div>
              ))}
            </div>
          </div>
          {panel && (
            <aside
              className="absolute inset-0 z-20 flex flex-col bg-neutral-900 sm:static sm:w-80 sm:border-l sm:border-white/10 lg:w-96"
              aria-label={panel === 'chat' ? 'Chat' : panel === 'participants' ? 'Participants' : 'Host controls'}
            >
              {panel === 'chat' && <ChatPanel chat={chat} onClose={() => setPanel(null)} />}
              {panel === 'participants' && <ParticipantsPanel onClose={() => setPanel(null)} />}
              {panel === 'moderation' && isModerator && <ModerationPanel onClose={() => setPanel(null)} />}
            </aside>
          )}
        </div>

        {/* A HOST_AND_SELF_ONLY attendee's camera, microphone and screen share live in the audience room. */}
        {/* A moderator's local recording also mixes in the attendees, who are heard in their audience rooms. */}
        <StageRoomContext.Provider value={room}>
          <AudienceRoomsContext.Provider value={isModerator ? audienceRooms : NO_ROOMS}>
            <RoomContext.Provider value={ownAudienceRoom ?? room}>
              <ControlBar
                panel={panel}
                togglePanel={togglePanel}
                unreadChat={unreadChat}
                onReaction={(emoji) => showReaction(emoji, 'You')}
                onLocalRecordingChange={setMyLocalRecording}
              />
            </RoomContext.Provider>
          </AudienceRoomsContext.Provider>
        </StageRoomContext.Provider>
        <RoomAudioRenderer />
        {isModerator &&
          audienceRooms.map((r, i) => (
            <RoomContext.Provider key={r.name || i} value={r}>
              <RoomAudioRenderer />
            </RoomContext.Provider>
          ))}
        <ConnectionStateToast />
        <UnmuteRequestDialog request={unmuteRequest} onClose={() => setUnmuteRequest(null)} />
      </div>
    </MeetingRoomContext.Provider>
  );
}
