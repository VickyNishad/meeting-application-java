import { useMemo, useState } from 'react';
import { useParticipants } from '@livekit/components-react';
import { RoomEvent, type Participant, type Room } from 'livekit-client';
import { Hand, Lock, Mic, MicOff, MoreVertical, MonitorUp, Search, Users, Video, VideoOff } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Avatar, Badge, Input } from '@/components/ui/primitives';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/overlays';
import { ConfirmDialog } from '@/components/common';
import { moderationApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';
import type { MediaKindKey, MediaOverride, MediaPermission, MeetingRole } from '@/lib/api/types';
import { cn } from '@/lib/utils';
import { displayNameOf, useMeetingRoom } from './roomContext';
import { PanelChrome, darkInput } from './PanelChrome';
import { useOwnAudienceParticipant, useRoomParticipants } from './useAudienceRoom';
import { useSpeakingIndicator } from './speaking';
import { isModeratorRole, roleFromMetadata } from './roomData';

const NO_ROOMS: Room[] = [];
const ROLE_LABEL: Record<MeetingRole, string> = { HOST: 'Host', CO_HOST: 'Co-host', PARTICIPANT: 'Participant', GUEST: 'Guest' };
const ROLE_ORDER: Record<MeetingRole, number> = { HOST: 0, CO_HOST: 1, PARTICIPANT: 2, GUEST: 3 };
/** One chip style for roles, sharing and restrictions. */
const CHIP = 'h-5 gap-1 rounded-md border-0 px-1.5 text-[10px] font-semibold uppercase tracking-wide';
const ROLE_CHIP: Record<MeetingRole, string> = {
  HOST: 'bg-primary/20 text-sky-200',
  CO_HOST: 'bg-violet-500/20 text-violet-200',
  PARTICIPANT: 'bg-white/10 text-neutral-200',
  GUEST: 'bg-white/10 text-neutral-300',
};
/** Microphone / camera state, one size for both. */
const STATUS = 'flex size-7 items-center justify-center rounded-full';

function useParticipantRole(p: Participant): MeetingRole {
  const { roleOverrides, join, myRole } = useMeetingRoom();
  if (p.identity === join.participantId) return myRole;
  return roleOverrides[p.identity] ?? roleFromMetadata(p.metadata) ?? 'PARTICIPANT';
}

const MEDIA_KINDS: { key: MediaKindKey; label: string; video: boolean }[] = [
  { key: 'microphone', label: 'Microphone', video: false },
  { key: 'camera', label: 'Camera', video: true },
  { key: 'screenShare', label: 'Screen sharing', video: true },
];
const PERMISSION_LABEL: Record<MediaPermission, string> = { ALLOWED: 'allowed', RESTRICTED: 'restricted' };
const OVERRIDE_KEY = { microphone: 'microphoneOverride', camera: 'cameraOverride', screenShare: 'screenShareOverride' } as const;

/** The host's per-participant media permissions: follow the meeting, allow, or restrict (the server enforces them). */
function MediaPermissionItems({ p, name }: { p: Participant; name: string }) {
  const { meeting, mediaPermissions, participantMedia, setParticipantMedia } = useMeetingRoom();
  const media = participantMedia[p.identity];
  const change = async (key: MediaKindKey, label: string, value: MediaOverride) => {
    try {
      setParticipantMedia(await moderationApi.participantMedia(meeting.id, p.identity, { [key]: value }));
      toast.success(
        value === 'DEFAULT' ? `${name}: ${label.toLowerCase()} follows the meeting setting` : `${name}: ${label.toLowerCase()} ${PERMISSION_LABEL[value]}`,
      );
    } catch (e) {
      toast.error(errorMessage(e));
    }
  };
  return (
    <>
      {MEDIA_KINDS.filter((k) => !k.video || meeting.mediaMode === 'VIDEO').map((k) => {
        const override = media?.[OVERRIDE_KEY[k.key]] ?? null;
        return (
          <div key={k.key}>
            <DropdownMenuSeparator />
            <DropdownMenuLabel>{k.label}</DropdownMenuLabel>
            <DropdownMenuRadioGroup value={override ?? 'DEFAULT'} onValueChange={(v) => void change(k.key, k.label, v as MediaOverride)}>
              <DropdownMenuRadioItem value="DEFAULT">Meeting setting ({PERMISSION_LABEL[mediaPermissions[k.key]]})</DropdownMenuRadioItem>
              <DropdownMenuRadioItem value="ALLOWED">Allow</DropdownMenuRadioItem>
              <DropdownMenuRadioItem value="RESTRICTED">Restrict</DropdownMenuRadioItem>
            </DropdownMenuRadioGroup>
          </div>
        );
      })}
    </>
  );
}

function ParticipantRow({ p, onRemove }: { p: Participant; onRemove: (p: Participant) => void }) {
  const { meeting, isModerator, myRole, raisedHands, names, participantMedia } = useMeetingRoom();
  const role = useParticipantRole(p);
  const speaking = useSpeakingIndicator(p);
  const name = displayNameOf(p, names);
  const hand = raisedHands[p.identity];
  const mic = p.isMicrophoneEnabled;
  const cam = p.isCameraEnabled;
  const sharing = p.isScreenShareEnabled;
  const canModerate = isModerator && !p.isLocal && role !== 'HOST';
  // The host sees what each participant may not use (hosts and co-hosts always may).
  const media = myRole === 'HOST' && !isModeratorRole(role) ? participantMedia[p.identity] : undefined;
  const restricted = media
    ? MEDIA_KINDS.filter((k) => (!k.video || meeting.mediaMode === 'VIDEO') && !media[k.key]).map((k) => k.label)
    : [];

  const act = async (label: string, fn: () => Promise<void>) => {
    try {
      await fn();
      toast.success(label);
    } catch (e) {
      toast.error(errorMessage(e));
    }
  };

  return (
    <li className="mx-2 flex items-center gap-3 rounded-lg px-2 py-2 transition-colors hover:bg-white/[0.04]">
      <Avatar name={name} className={cn('size-9 bg-white/10 text-neutral-100', speaking && 'ring-2 ring-success ring-offset-2 ring-offset-neutral-900')} />
      <div className="min-w-0 flex-1">
        <p className="flex min-w-0 items-center gap-1.5 text-sm font-medium text-neutral-50">
          <span className="truncate">{name}</span>
          {p.isLocal && <span className="shrink-0 text-xs font-normal text-neutral-400">(you)</span>}
          {hand && <Hand className="size-4 shrink-0 text-warning" aria-label="Hand raised" />}
        </p>
        {(role === 'HOST' || role === 'CO_HOST' || role === 'GUEST' || sharing || restricted.length > 0) && (
          <div className="mt-1 flex flex-wrap items-center gap-1">
            {role !== 'PARTICIPANT' && <Badge className={cn(CHIP, ROLE_CHIP[role])}>{ROLE_LABEL[role]}</Badge>}
            {sharing && (
              <Badge className={cn(CHIP, 'bg-primary/15 text-sky-300')}>
                <MonitorUp className="size-3" aria-hidden="true" /> Sharing
              </Badge>
            )}
            {restricted.map((label) => (
              <Badge key={label} className={cn(CHIP, 'bg-red-500/15 text-red-300')} title={`${label} restricted by the host`}>
                <Lock className="size-3" aria-hidden="true" /> {label}
              </Badge>
            ))}
          </div>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <span className={cn(STATUS, mic ? 'text-neutral-300' : 'bg-red-500/15 text-red-400')} aria-label={mic ? 'Microphone on' : 'Microphone muted'}>
          {mic ? <Mic className="size-3.5" aria-hidden="true" /> : <MicOff className="size-3.5" aria-hidden="true" />}
        </span>
        {meeting.mediaMode === 'VIDEO' && (
          <span className={cn(STATUS, cam ? 'text-neutral-300' : 'bg-red-500/15 text-red-400')} aria-label={cam ? 'Camera on' : 'Camera off'}>
            {cam ? <Video className="size-3.5" aria-hidden="true" /> : <VideoOff className="size-3.5" aria-hidden="true" />}
          </span>
        )}
      </div>
      {/* Rows without actions keep the space, so every row's status icons line up. */}
      {isModerator && !canModerate && <span className="size-8 shrink-0" aria-hidden="true" />}
      {canModerate && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              className="size-8 shrink-0 rounded-lg border border-white/10 text-neutral-200 hover:bg-white/10 hover:text-white focus-visible:ring-offset-neutral-900"
              aria-label={`Actions for ${name}`}
            >
              <MoreVertical aria-hidden="true" />
            </Button>
          </DropdownMenuTrigger>
          {/* Long for a host (media permissions): never taller than the space on screen, scrolls instead. */}
          <DropdownMenuContent align="end" collisionPadding={8} className="max-h-[var(--radix-dropdown-menu-content-available-height)] overflow-y-auto [color-scheme:dark]">
            <DropdownMenuLabel>{name}</DropdownMenuLabel>
            {mic ? (
              <DropdownMenuItem onSelect={() => void act(`Muted ${name}`, () => moderationApi.mute(meeting.id, p.identity, 'MICROPHONE'))}>
                <MicOff aria-hidden="true" /> Mute microphone
              </DropdownMenuItem>
            ) : (
              <DropdownMenuItem onSelect={() => void act(`Asked ${name} to unmute`, () => moderationApi.requestUnmute(meeting.id, p.identity, 'MICROPHONE'))}>
                <Mic aria-hidden="true" /> Ask to unmute
              </DropdownMenuItem>
            )}
            {meeting.mediaMode === 'VIDEO' &&
              (cam ? (
                <DropdownMenuItem onSelect={() => void act(`Stopped ${name}'s camera`, () => moderationApi.mute(meeting.id, p.identity, 'CAMERA'))}>
                  <VideoOff aria-hidden="true" /> Stop camera
                </DropdownMenuItem>
              ) : (
                <DropdownMenuItem onSelect={() => void act(`Asked ${name} to start video`, () => moderationApi.requestUnmute(meeting.id, p.identity, 'CAMERA'))}>
                  <Video aria-hidden="true" /> Ask to start camera
                </DropdownMenuItem>
              ))}
            {sharing && (
              <DropdownMenuItem onSelect={() => void act(`Stopped ${name}'s screen share`, () => moderationApi.mute(meeting.id, p.identity, 'SCREEN_SHARE'))}>
                <MonitorUp aria-hidden="true" /> Stop screen share
              </DropdownMenuItem>
            )}
            {myRole === 'HOST' && (
              <>
                {role !== 'GUEST' && <DropdownMenuSeparator />}
                {role === 'CO_HOST' ? (
                  <DropdownMenuItem onSelect={() => void act(`${name} is no longer a co-host`, () => moderationApi.setRole(meeting.id, p.identity, 'PARTICIPANT'))}>
                    Remove co-host
                  </DropdownMenuItem>
                ) : (
                  role !== 'GUEST' && (
                    <DropdownMenuItem onSelect={() => void act(`${name} is now a co-host`, () => moderationApi.setRole(meeting.id, p.identity, 'CO_HOST'))}>
                      Make co-host
                    </DropdownMenuItem>
                  )
                )}
                {!isModeratorRole(role) && <MediaPermissionItems p={p} name={name} />}
              </>
            )}
            <DropdownMenuSeparator />
            <DropdownMenuItem destructive onSelect={() => onRemove(p)}>
              Remove from meeting
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </li>
  );
}

/** Membership and device changes; not speaker changes, which each row follows for its own participant. */
const LIST_EVENTS = [RoomEvent.ParticipantConnected, RoomEvent.ParticipantDisconnected, RoomEvent.ParticipantMetadataChanged,
  RoomEvent.ParticipantNameChanged, RoomEvent.TrackPublished, RoomEvent.TrackUnpublished, RoomEvent.TrackSubscribed,
  RoomEvent.TrackUnsubscribed, RoomEvent.TrackMuted, RoomEvent.TrackUnmuted, RoomEvent.LocalTrackPublished,
  RoomEvent.LocalTrackUnpublished, RoomEvent.ConnectionStateChanged];

function ParticipantsList() {
  const stageParticipants = useParticipants({ updateOnlyOn: LIST_EVENTS });
  const { meeting, raisedHands, roleOverrides, join, myRole, names, stageOnly, isModerator, audienceRooms, waitingCount } = useMeetingRoom();
  const [query, setQuery] = useState('');
  // HOST_AND_SELF_ONLY: attendees are in their audience rooms (the stage room hides them); only the stage sees them.
  const audience = useRoomParticipants(isModerator ? audienceRooms : NO_ROOMS);
  // An attendee's own row comes from its audience room, where its microphone and speaking state are (in the stage
  // room it is a hidden watcher that publishes nothing).
  const ownAudience = useOwnAudienceParticipant(stageOnly && !isModerator ? audienceRooms : NO_ROOMS);
  const participants = useMemo(
    () => [...(ownAudience ? stageParticipants.filter((p) => !p.isLocal) : stageParticipants), ...(ownAudience ? [ownAudience] : []), ...audience],
    [stageParticipants, audience, ownAudience],
  );
  const [removing, setRemoving] = useState<Participant | null>(null);

  const roleOf = (p: Participant): MeetingRole =>
    p.identity === join.participantId ? myRole : (roleOverrides[p.identity] ?? roleFromMetadata(p.metadata) ?? 'PARTICIPANT');
  // Webinar attendees list the stage and themselves; the stage lists everyone.
  const listed = stageOnly && !isModerator ? participants.filter((p) => p.isLocal || isModeratorRole(roleOf(p))) : participants;
  const nameOf = (p: Participant) => displayNameOf(p, names);
  const sorted = [...listed].sort((a, b) => {
    const ha = raisedHands[a.identity] ? 0 : 1;
    const hb = raisedHands[b.identity] ? 0 : 1;
    if (ha !== hb) return ha - hb;
    const ra = ROLE_ORDER[roleOf(a)];
    const rb = ROLE_ORDER[roleOf(b)];
    if (ra !== rb) return ra - rb;
    return nameOf(a).localeCompare(nameOf(b));
  });

  const needle = query.trim().toLowerCase();
  const shown = needle ? sorted.filter((p) => nameOf(p).toLowerCase().includes(needle)) : sorted;

  return (
    <>
      <div className="space-y-3 px-4 pb-2 pt-3">
        {stageOnly && !isModerator && (
          <p className="rounded-lg bg-white/5 px-3 py-2 text-xs leading-relaxed text-neutral-300">
            This is a webinar: you see the host and co-hosts, and only they see and hear you.
          </p>
        )}
        {isModerator && waitingCount > 0 && (
          <p className="rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-xs leading-relaxed text-warning" role="status">
            {waitingCount === 1 ? '1 person is' : `${waitingCount} people are`} waiting to join. Admit them in Host controls.
          </p>
        )}
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-neutral-500" aria-hidden="true" />
          <label htmlFor="participant-search" className="sr-only">
            Search participants
          </label>
          <Input
            id="participant-search"
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search by name"
            className={cn(darkInput, 'pl-9')}
          />
        </div>
        <p className="text-[11px] font-semibold uppercase tracking-wider text-neutral-400">
          In the meeting{!(stageOnly && !isModerator) && ` · ${listed.length}`}
        </p>
      </div>
      <ul className="space-y-0.5 pb-3" aria-label={`${listed.length} participants`}>
        {shown.map((p) => (
          <ParticipantRow key={p.identity} p={p} onRemove={setRemoving} />
        ))}
      </ul>
      {needle && shown.length === 0 && <p className="px-4 py-6 text-center text-sm text-neutral-400">No one matches “{query.trim()}”.</p>}
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={`Remove ${removing ? nameOf(removing) : 'this participant'}?`}
        description="They will be disconnected and cannot rejoin this meeting with the same session."
        confirmLabel="Remove"
        onConfirm={async () => {
          if (!removing) return;
          await moderationApi.remove(meeting.id, removing.identity);
          toast.success(`${nameOf(removing)} was removed`);
        }}
      />
    </>
  );
}

export function ParticipantsPanel({ onClose }: { onClose: () => void }) {
  const { stageOnly, isModerator, presentCount } = useMeetingRoom();
  // The server count (one per person); attendees of a HOST_AND_SELF_ONLY meeting are not told how many others there are.
  const count = stageOnly && !isModerator ? null : presentCount;
  return (
    <PanelChrome title="Participants" icon={<Users aria-hidden="true" />} count={count} onClose={onClose}>
      <ParticipantsList />
    </PanelChrome>
  );
}
