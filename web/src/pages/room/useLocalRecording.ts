import { useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useRoomContext } from '@livekit/components-react';
import { RoomEvent, Track, type RemoteTrack } from 'livekit-client';
import { toast } from 'sonner';
import { recordingsApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';
import {
  LocalRecorder,
  RecorderError,
  detectLocalRecordingSupport,
  downloadBlob,
  recordingFileName,
} from '@/lib/recorder/localRecorder';
import { AudienceRoomsContext, StageRoomContext } from './useAudienceRoom';

export type LocalRecState = 'idle' | 'starting' | 'recording' | 'saving';

/**
 * Local (in-browser) recording of the meeting with server notification
 * (POST /local-recordings STARTED/STOPPED → broadcast recording indicator + audit).
 */
export function useLocalRecording(meetingId: string, participantId: string, waitToken: string, title: string) {
  // The room this participant publishes in (own microphone), and the room whose remote audio is recorded: the
  // same room, except for a HOST_AND_SELF_ONLY attendee (publishes in its audience room, hears the stage room). A
  // HOST_AND_SELF_ONLY moderator also hears the attendees, in their audience rooms: they are recorded too.
  const room = useRoomContext();
  const stage = useContext(StageRoomContext) ?? room;
  const audienceRooms = useContext(AudienceRoomsContext);
  const heard = useMemo(() => [stage, ...audienceRooms], [stage, audienceRooms]);
  const support = useMemo(() => detectLocalRecordingSupport(), []);
  const [state, setState] = useState<LocalRecState>('idle');
  const [elapsed, setElapsed] = useState(0);
  const [lastError, setLastError] = useState<string | null>(null);
  const recRef = useRef<LocalRecorder | null>(null);

  const notify = useCallback(
    (s: 'STARTED' | 'STOPPED') => recordingsApi.localState(meetingId, participantId, s, waitToken),
    [meetingId, participantId, waitToken],
  );

  const collectAudioTracks = useCallback((): MediaStreamTrack[] => {
    const tracks: MediaStreamTrack[] = [];
    const mic = room.localParticipant.getTrackPublication(Track.Source.Microphone)?.track?.mediaStreamTrack;
    if (mic) tracks.push(mic);
    for (const r of heard) {
      for (const p of r.remoteParticipants.values()) {
        for (const pub of p.audioTrackPublications.values()) {
          const t = pub.track?.mediaStreamTrack;
          if (t) tracks.push(t);
        }
      }
    }
    return tracks;
  }, [room, heard]);

  const finalize = useCallback(
    async (blobPromise: Promise<Blob>, rec: LocalRecorder) => {
      setState('saving');
      try {
        const blob = await blobPromise;
        downloadBlob(blob, recordingFileName(title, rec.extension));
        toast.success('Local recording saved to your device');
      } catch (e) {
        const msg = e instanceof RecorderError ? e.message : `Could not save the recording: ${errorMessage(e)}`;
        setLastError(msg);
        toast.error(msg);
      } finally {
        recRef.current = null;
        setState('idle');
        notify('STOPPED').catch((e: unknown) => toast.error(`Could not notify the server that recording stopped: ${errorMessage(e)}`));
      }
    },
    [notify, title],
  );

  const start = useCallback(async () => {
    if (!support.supported || recRef.current) return;
    setLastError(null);
    setState('starting');
    const rec = new LocalRecorder({
      audioTracks: collectAudioTracks,
      onAutoStop: (reason, error) => {
        if (reason === 'capture-ended') toast.info('Screen capture ended — saving your recording.');
        if (error) {
          setLastError(error.message);
          toast.error(error.message);
        }
        void finalize(rec.waitForResult(), rec);
      },
    });
    try {
      await rec.acquire();
    } catch (e) {
      const msg = e instanceof RecorderError ? e.message : errorMessage(e);
      setLastError(msg);
      toast.error(msg);
      setState('idle');
      return;
    }
    // Participants must be informed before anything is recorded.
    try {
      await notify('STARTED');
    } catch (e) {
      rec.abort();
      const msg = `Recording was not started because the server could not be notified: ${errorMessage(e)}`;
      setLastError(msg);
      toast.error(msg);
      setState('idle');
      return;
    }
    try {
      rec.start();
    } catch (e) {
      rec.abort();
      const msg = e instanceof RecorderError ? e.message : errorMessage(e);
      setLastError(msg);
      toast.error(msg);
      setState('idle');
      notify('STOPPED').catch(() => undefined);
      return;
    }
    recRef.current = rec;
    setElapsed(0);
    setState('recording');
  }, [support.supported, collectAudioTracks, notify, finalize]);

  const stop = useCallback(() => {
    const rec = recRef.current;
    if (!rec) return;
    void finalize(rec.stop(), rec);
  }, [finalize]);

  // Mix in audio of participants who join / publish during the recording.
  useEffect(() => {
    if (state !== 'recording') return;
    const onSub = (track: RemoteTrack) => {
      if (track.kind === Track.Kind.Audio) recRef.current?.addAudioTrack(track.mediaStreamTrack);
    };
    const onUnsub = (track: RemoteTrack) => {
      if (track.kind === Track.Kind.Audio) recRef.current?.removeAudioTrack(track.mediaStreamTrack.id);
    };
    const onLocal = () => {
      const mic = room.localParticipant.getTrackPublication(Track.Source.Microphone)?.track?.mediaStreamTrack;
      if (mic) recRef.current?.addAudioTrack(mic);
    };
    for (const r of heard) {
      r.on(RoomEvent.TrackSubscribed, onSub);
      r.on(RoomEvent.TrackUnsubscribed, onUnsub);
    }
    room.on(RoomEvent.LocalTrackPublished, onLocal);
    const timer = setInterval(() => setElapsed((s) => s + 1), 1000);
    return () => {
      for (const r of heard) {
        r.off(RoomEvent.TrackSubscribed, onSub);
        r.off(RoomEvent.TrackUnsubscribed, onUnsub);
      }
      room.off(RoomEvent.LocalTrackPublished, onLocal);
      clearInterval(timer);
    };
  }, [state, room, heard]);

  // Leaving the room mid-recording still saves the file.
  useEffect(() => {
    return () => {
      const rec = recRef.current;
      if (rec?.active) {
        rec
          .stop()
          .then((blob) => downloadBlob(blob, recordingFileName(title, rec.extension)))
          .catch(() => undefined);
        notify('STOPPED').catch(() => undefined);
      }
    };
  }, [notify, title]);

  return { support, state, elapsed, lastError, start, stop };
}
