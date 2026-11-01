import { useMemo, useState } from 'react';
import { useParticipants } from '@livekit/components-react';
import { RoomEvent, type Participant, type Room } from 'livekit-client';
import { Hand, Mic, MicOff, MoreVertical, MonitorUp, Video, VideoOff } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Avatar, Badge } from '@/components/ui/primitives';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/overlays';
import { ConfirmDialog } from '@/components/common';
import { moderationApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';
import type { MeetingRole } from '@/lib/api/types';
import { cn } from '@/lib/utils';
import { displayNameOf, useMeetingRoom } from './roomContext';
import { PanelChrome } from './PanelChrome';
import { useOwnAudienceParticipant, useRoomParticipants } from './useAudienceRoom';
import { useSpeakingIndicator } from './speaking';
import { isModeratorRole, roleFromMetadata } from './roomData';

const NO_ROOMS: Room[] = [];
const ROLE_LABEL: Record<MeetingRole, string> = { HOST: 'Host', CO_HOST: 'Co-host', PARTICIPANT: 'Participant', GUEST: 'Guest' };
const ROLE_ORDER: Record<MeetingRole, number> = { HOST: 0, CO_HOST: 1, PARTICIPANT: 2, GUEST: 3 };

function useParticipantRole(p: Participant): MeetingRole {
  const { roleOverrides, join, myRole } = useMeetingRoom();
  if (p.identity === join.participantId) return myRole;
  return roleOverrides[p.identity] ?? roleFromMetadata(p.metadata) ?? 'PARTICIPANT';
}

function ParticipantRow({ p, onRemove }: { p: Participant; onRemove: (p: Participant) => void }) {
  const { meeting, isModerator, myRole, raisedHands, names } = useMeetingRoom();
  const role = useParticipantRole(p);
  const speaking = useSpeakingIndicator(p);
  const name = displayNameOf(p, names);
  const hand = raisedHands[p.identity];
  const mic = p.isMicrophoneEnabled;
  const cam = p.isCameraEnabled;
  const sharing = p.isScreenShareEnabled;
  const canModerate = isModerator && !p.isLocal && role !== 'HOST';

  const act = async (label: string, fn: () => Promise<void>) => {
    try {
      await fn();
      toast.success(label);
    } catch (e) {
      toast.error(errorMessage(e));
    }
  };

  return (
    <li className="flex items-center gap-3 px-4 py-2.5">
      <Avatar name={name} className={cn('size-9 bg-white/10 text-neutral-100', speaking && 'ring-2 ring-success')} />
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-1.5 truncate text-sm font-medium">
          {name}
          {p.isLocal && <span className="text-neutral-400">(you)</span>}
          {hand && <Hand className="size-4 text-warning" aria-label="Hand raised" />}
        </p>
        <div className="mt-0.5 flex items-center gap-1.5">
          {role !== 'PARTICIPANT' && <Badge className="bg-white/10 text-[10px] text-neutral-200">{ROLE_LABEL[role]}</Badge>}
          {sharing && <MonitorUp className="size-3.5 text-primary" aria-label="Sharing screen" />}
        </div>
      </div>
      <span aria-label={mic ? 'Microphone on' : 'Microphone muted'}>
        {mic ? <Mic className="size-4 text-neutral-300" aria-hidden="true" /> : <MicOff className="size-4 text-red-400" aria-hidden="true" />}
      </span>
      {meeting.mediaMode === 'VIDEO' && (
        <span aria-label={cam ? 'Camera on' : 'Camera off'}>
          {cam ? <Video className="size-4 text-neutral-300" aria-hidden="true" /> : <VideoOff className="size-4 text-red-400" aria-hidden="true" />}
        </span>
      )}
      {canModerate && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" className="size-8 text-neutral-200 hover:bg-white/10" aria-label={`Actions for ${name}`}>
              <MoreVertical aria-hidden="true" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
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
                <DropdownMenuSeparator />
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
  const { meeting, raisedHands, roleOverrides, join, myRole, names, stageOnly, isModerator, audienceRooms } = useMeetingRoom();
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

  return (
    <>
      {stageOnly && !isModerator && (
        <p className="border-b border-white/5 px-4 py-2 text-xs text-neutral-400">
          This is a webinar: you see the host and co-hosts, and only they see and hear you.
        </p>
      )}
      <ul className="divide-y divide-white/5" aria-label={`${listed.length} participants`}>
        {sorted.map((p) => (
          <ParticipantRow key={p.identity} p={p} onRemove={setRemoving} />
        ))}
      </ul>
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
  const title = stageOnly && !isModerator ? 'Participants' : presentCount === null ? 'Participants' : `Participants (${presentCount})`;
  return (
    <PanelChrome title={title} onClose={onClose}>
      <ParticipantsList />
    </PanelChrome>
  );
}
