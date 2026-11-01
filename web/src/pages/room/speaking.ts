import { useEffect, useLayoutEffect, useState } from 'react';
import { ConnectionQuality, ParticipantEvent, RoomEvent, Track, type Participant, type Room } from 'livekit-client';

/**
 * How long a tile stays highlighted after its speaker goes quiet (the silence threshold). Bridges the pauses between
 * words so the border does not flicker; the media server already smooths its own detection (livekit `audio:` config).
 */
export const SPEAKING_RELEASE_MS = 600;

/**
 * After a microphone is turned back on, the last "speaking" report is from before it was turned off: it is ignored
 * until the media server sends a fresh one, or for at most this long (2.5 of LiveKit's 400 ms update intervals).
 */
export const FRESH_REPORT_MS = 1000;

/**
 * Whether a participant is speaking right now, from the media server's voice-activity detection: the SFU measures
 * the audio level of the participant's microphone track (RTP audio-level extension; LiveKit 1.13 counts only the
 * MICROPHONE source, so screen-share audio never makes someone "speak") and reports who is above its threshold for
 * long enough (`audio.active_level`, `min_percentile`, `update_interval` in the LiveKit config). A microphone that is
 * on but silent, or carrying only background noise below the threshold, is not speaking.
 *
 * Also never speaking while the microphone is off (the last report from before a mute may still say "speaking") or
 * while the participant's connection is lost (no fresh reports arrive).
 */
export function isSpeakingNow(p: Pick<Participant, 'isSpeaking' | 'isMicrophoneEnabled' | 'connectionQuality'>): boolean {
  return p.isSpeaking && p.isMicrophoneEnabled && p.connectionQuality !== ConnectionQuality.Lost;
}

/**
 * Turns the raw speaking state into the displayed one: on at once when speech starts, off only after `releaseMs`
 * of continuous silence. A hard stop (microphone off, connection lost) can skip the wait with `immediate`.
 */
export class SpeakingHold {
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor(
    private readonly releaseMs: number,
    private readonly onChange: (speaking: boolean) => void,
    private shown = false,
  ) {}

  update(speaking: boolean, immediate = false): void {
    if (speaking) {
      this.cancel();
      this.set(true);
    } else if (immediate || this.releaseMs <= 0) {
      this.cancel();
      this.set(false);
    } else if (this.shown && this.timer === undefined) {
      this.timer = setTimeout(() => {
        this.timer = undefined;
        this.set(false);
      }, this.releaseMs);
    }
  }

  dispose(): void {
    this.cancel();
  }

  private set(value: boolean) {
    if (value === this.shown) return;
    this.shown = value;
    this.onChange(value);
  }

  private cancel() {
    if (this.timer !== undefined) clearTimeout(this.timer);
    this.timer = undefined;
  }
}

const MIC_EVENTS = [ParticipantEvent.TrackMuted, ParticipantEvent.TrackUnmuted, ParticipantEvent.TrackPublished,
  ParticipantEvent.TrackUnpublished, ParticipantEvent.LocalTrackPublished, ParticipantEvent.LocalTrackUnpublished,
  ParticipantEvent.TrackSubscribed, ParticipantEvent.TrackUnsubscribed] as const;

/**
 * The highlighted state of one participant's tile. Listens only to that participant's own events, so a change
 * re-renders that tile alone, never the grid; listeners and timers go away with the tile (participant left).
 * Evaluated before paint, so a tile that starts showing another participant never shows the previous one's state.
 */
export function useSpeakingIndicator(participant: Participant | undefined, releaseMs = SPEAKING_RELEASE_MS): boolean {
  const [speaking, setSpeaking] = useState(false);
  useLayoutEffect(() => {
    if (!participant) {
      setSpeaking(false);
      return;
    }
    let micWasOn = participant.isMicrophoneEnabled;
    let awaitingFresh = false;
    let freshTimer: ReturnType<typeof setTimeout> | undefined;
    const raw = () => !awaitingFresh && isSpeakingNow(participant);
    const initial = raw();
    setSpeaking(initial);
    const hold = new SpeakingHold(releaseMs, setSpeaking, initial);
    const stopWaiting = () => {
      awaitingFresh = false;
      if (freshTimer !== undefined) clearTimeout(freshTimer);
      freshTimer = undefined;
    };
    // A report from the media server: fresh by definition.
    const onSpeaking = () => {
      stopWaiting();
      hold.update(raw());
    };
    // Microphone on/off or connection lost: stop at once instead of after the silence threshold.
    const onHardChange = () => {
      const micOn = participant.isMicrophoneEnabled;
      if (micOn && !micWasOn) {
        stopWaiting();
        awaitingFresh = true;
        freshTimer = setTimeout(() => {
          freshTimer = undefined;
          awaitingFresh = false;
          hold.update(raw());
        }, FRESH_REPORT_MS);
      }
      micWasOn = micOn;
      // Immediate only when the microphone is really off or the connection really lost; a routine event (quality
      // update, a track subscribed) during a short pause between words goes through the normal silence threshold.
      const hardStop = !micOn || participant.connectionQuality === ConnectionQuality.Lost;
      hold.update(raw(), hardStop);
    };
    const onTrack = (pub?: { source?: Track.Source }) => {
      if (!pub || pub.source === undefined || pub.source === Track.Source.Microphone) onHardChange();
    };
    participant.on(ParticipantEvent.IsSpeakingChanged, onSpeaking);
    participant.on(ParticipantEvent.ConnectionQualityChanged, onHardChange);
    for (const e of MIC_EVENTS) participant.on(e, onTrack);
    return () => {
      participant.off(ParticipantEvent.IsSpeakingChanged, onSpeaking);
      participant.off(ParticipantEvent.ConnectionQualityChanged, onHardChange);
      for (const e of MIC_EVENTS) participant.off(e, onTrack);
      stopWaiting();
      hold.dispose();
    };
  }, [participant, releaseMs]);
  return speaking;
}

const CONNECTION_EVENTS = [RoomEvent.SignalReconnecting, RoomEvent.Reconnecting, RoomEvent.Reconnected,
  RoomEvent.Disconnected] as const;

/**
 * Clears everyone's speaking state in a room whenever its connection is interrupted, comes back or ends. While it
 * is interrupted no reports arrive, and afterwards the media server only reports changes from a fresh "nobody is
 * speaking" (a report sent during the interruption is lost), so a "speaking" from before would otherwise stay on.
 * Covers every room this client is in: the stage room and the audience rooms of a HOST_AND_SELF_ONLY session.
 * (`setIsSpeaking` is the client SDK's own setter for the server's speaker reports.)
 */
export function useClearSpeakingOnReconnect(rooms: Room[]): void {
  useEffect(() => {
    const offs = rooms.map((room) => {
      const clear = () => {
        room.localParticipant?.setIsSpeaking(false);
        for (const p of room.remoteParticipants.values()) p.setIsSpeaking(false);
      };
      for (const e of CONNECTION_EVENTS) room.on(e, clear);
      return () => {
        for (const e of CONNECTION_EVENTS) room.off(e, clear);
      };
    });
    return () => offs.forEach((off) => off());
  }, [rooms]);
}
