import { createContext, useContext } from 'react';
import type { JoinResponse, MediaPermissions, Meeting, MeetingRole, ParticipantMedia, ScreenSharePolicy } from '@/lib/api/types';
import type { Room } from 'livekit-client';

export type EndReason = 'left' | 'ended' | 'removed';

export interface MeetingRoomState {
  meeting: Meeting;
  join: JoinResponse;
  myRole: MeetingRole;
  isModerator: boolean;
  /** Webinar (HOSTS_ONLY): attendees see and hear only the stage. */
  stageOnly: boolean;
  /** Identities of the host and co-hosts present. */
  stageIdentities: string[];
  locked: boolean;
  setLocked: (l: boolean) => void;
  screenSharePolicy: ScreenSharePolicy;
  setScreenSharePolicy: (p: ScreenSharePolicy) => void;
  waitingRoomEnabled: boolean;
  chatEnabled: boolean;
  setChatEnabled: (v: boolean) => void;
  setWaitingRoomEnabled: (v: boolean) => void;
  /** Role overrides from `role-changed` signals, keyed by participant identity. */
  roleOverrides: Record<string, MeetingRole>;
  /** participantId → display name of participants with a raised hand. */
  raisedHands: Record<string, string>;
  setHand: (identity: string, raised: boolean) => void;
  waitingCount: number;
  cloudRecording: boolean;
  cloudRecordingAvailable: boolean;
  setCloudRecording: (v: boolean) => void;
  /** participantId → display name of participants recording locally. */
  localRecorders: Record<string, string>;
  /** Real names by identity, where the media server shows "Attendee" (stage of a webinar). */
  names: Record<string, string>;
  /** People in the session according to the server (null until the room state is loaded). */
  presentCount: number | null;
  /**
   * The connected audience rooms of a HOST_AND_SELF_ONLY session: an attendee's own, or (stage) every attendee's.
   * Empty otherwise.
   */
  audienceRooms: Room[];
  /** Meeting-wide media permissions for participants (hosts and co-hosts always may publish). */
  mediaPermissions: MediaPermissions;
  setMediaPermissions: (p: MediaPermissions) => void;
  /** Media rights by participant id: everyone's for the host, otherwise one's own. */
  participantMedia: Record<string, ParticipantMedia>;
  setParticipantMedia: (m: ParticipantMedia) => void;
  finish: (reason: EndReason) => void;
}

export const MeetingRoomContext = createContext<MeetingRoomState | null>(null);

export function useMeetingRoom(): MeetingRoomState {
  const ctx = useContext(MeetingRoomContext);
  if (!ctx) throw new Error('useMeetingRoom must be used inside the meeting room');
  return ctx;
}

/** The name to show for a participant: the real one when the media server only knows "Attendee". */
export function displayNameOf(p: { identity: string; name?: string }, names: Record<string, string>): string {
  return names[p.identity] ?? p.name ?? p.identity;
}
