import { createContext, useEffect, useRef, useState } from 'react';
import { ConnectionState, Room, RoomEvent, type LocalParticipant, type RemoteParticipant, type RoomOptions } from 'livekit-client';
import type { AudienceRoomGrant } from '@/lib/api/types';
import type { MediaState } from '@/lib/joinStore';
import { isMediaDeviceError } from './mediaErrors';
import { isPublishPermissionError } from './mediaRights';

/**
 * The audience rooms of a HOST_AND_SELF_ONLY session (media connections next to the stage room). Every attendee has
 * a room of its own, so the media server never shows one attendee to another. What may be done there is fixed by
 * the server-issued tokens: an attendee publishes its camera and microphone in its room but cannot subscribe; the
 * host and co-hosts join every attendee's room hidden, subscribing and publishing nothing.
 *
 * Connects to every granted room not yet connected, publishes `media` there once connected (attendees), and
 * disconnects rooms that are no longer granted and everything on unmount. A connection that fails or drops is
 * dropped from the list and reported through `onLost`, so the caller fetches fresh grants (tokens are short-lived)
 * and the room is connected again. Returns the connected rooms.
 */
export function useAudienceRooms(
  url: string | undefined,
  grants: AudienceRoomGrant[] | undefined,
  options: RoomOptions,
  media: MediaState | null,
  onError: (e: unknown) => void,
  onLost: () => void,
): Room[] {
  const [rooms, setRooms] = useState<Room[]>([]);
  const live = useRef(new Map<string, Room>());
  // Read when a room connects: later values (re-renders) must not reconnect or republish.
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const mediaRef = useRef(media);
  mediaRef.current = media;
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;
  const onLostRef = useRef(onLost);
  onLostRef.current = onLost;
  const grantsRef = useRef(grants);
  grantsRef.current = grants;
  // New tokens (a refetch) are a reason to connect rooms that are missing; connected rooms keep their connection.
  const key = (grants ?? []).map((g) => `${g.roomName}:${g.token}`).join('|');

  useEffect(() => {
    const current = live.current;
    const wanted = new Map((url ? grantsRef.current ?? [] : []).map((g) => [g.roomName, g]));
    for (const [name, r] of current) {
      if (!wanted.has(name)) {
        current.delete(name);
        void r.disconnect();
      }
    }
    const forget = (name: string, r: Room) => {
      if (current.get(name) !== r) return false; // already replaced, or closed by us
      current.delete(name);
      setRooms([...current.values()]);
      return true;
    };
    for (const [name, grant] of wanted) {
      if (current.has(name)) continue;
      const r = new Room(optionsRef.current);
      current.set(name, r);
      r.on(RoomEvent.Disconnected, () => {
        if (forget(name, r)) onLostRef.current();
      });
      void (async () => {
        try {
          await r.connect(url!, grant.token);
        } catch (e) {
          if (forget(name, r)) {
            onErrorRef.current(e);
            onLostRef.current();
          }
          return;
        }
        const m = mediaRef.current;
        const enable = async (on: boolean | undefined, turnOn: () => Promise<unknown>) => {
          if (!on || current.get(name) !== r) return;
          try {
            await turnOn();
          } catch (e) {
            // Device problems (missing, blocked) are not connection failures: report others only.
            if (!isMediaDeviceError(e) && !isPublishPermissionError(e)) onErrorRef.current(e);
          }
        };
        await enable(m?.audioEnabled, () => r.localParticipant.setMicrophoneEnabled(true));
        await enable(m?.videoEnabled, () => r.localParticipant.setCameraEnabled(true));
      })();
    }
    setRooms([...current.values()]);
  }, [url, key]);

  useEffect(() => {
    const current = live.current;
    return () => {
      const all = [...current.values()];
      current.clear();
      for (const r of all) void r.disconnect();
    };
  }, []);

  return rooms;
}

/** Remote participants of some rooms, kept current with their join/leave/metadata events. */
export function useRoomParticipants(rooms: Room[]): RemoteParticipant[] {
  const [list, setList] = useState<RemoteParticipant[]>([]);
  useEffect(() => {
    const update = () => setList(rooms.flatMap((room) => [...room.remoteParticipants.values()]));
    update();
    const events = [RoomEvent.ParticipantConnected, RoomEvent.ParticipantDisconnected, RoomEvent.ParticipantMetadataChanged,
      RoomEvent.Connected, RoomEvent.Reconnected, RoomEvent.TrackPublished, RoomEvent.TrackUnpublished,
      RoomEvent.TrackMuted, RoomEvent.TrackUnmuted] as const;
    for (const room of rooms) for (const e of events) room.on(e, update);
    return () => {
      for (const room of rooms) for (const e of events) room.off(e, update);
    };
  }, [rooms]);
  return list;
}

/**
 * A HOST_AND_SELF_ONLY attendee's own participant in its audience room (where it publishes, so where its microphone,
 * camera and speaking state live), while that room is connected; re-renders on its device changes.
 */
export function useOwnAudienceParticipant(rooms: Room[]): LocalParticipant | null {
  const room = rooms[0] ?? null;
  const [, setVersion] = useState(0);
  useEffect(() => {
    if (!room) return;
    const bump = () => setVersion((v) => v + 1);
    const events = [RoomEvent.Connected, RoomEvent.ConnectionStateChanged, RoomEvent.LocalTrackPublished,
      RoomEvent.LocalTrackUnpublished, RoomEvent.TrackMuted, RoomEvent.TrackUnmuted] as const;
    for (const e of events) room.on(e, bump);
    return () => {
      for (const e of events) room.off(e, bump);
    };
  }, [room]);
  return room && room.state === ConnectionState.Connected ? room.localParticipant : null;
}

/** The stage room, for code that runs inside an audience room's context but needs the stage (local recording). */
export const StageRoomContext = createContext<Room | null>(null);

/** The audience rooms, for code that runs inside the stage room's context (a moderator's local recording). */
export const AudienceRoomsContext = createContext<Room[]>([]);
