import { useEffect } from 'react';
import { RoomEvent, Track, type LocalAudioTrack, type LocalTrackPublication, type Room, type TrackProcessor, type AudioProcessorOptions } from 'livekit-client';
import { AudioBlockedError, NOISE_FILTER_NAME, NoiseFilterProcessor, isNoiseFilterSupported } from '@/lib/audio/noiseFilter';

export interface NoiseFilterDeps {
  supported: boolean;
  create: () => TrackProcessor<Track.Kind.Audio, AudioProcessorOptions>;
  /** The filter could not be started: the plain microphone (with the browser's own processing) is used. */
  onUnavailable: (e: unknown) => void;
  /** Calls `retry` once on the next click or key press (the browser allows audio processing after one). */
  onUserGesture: (retry: () => void) => () => void;
}

const defaultDeps = (): NoiseFilterDeps => ({
  supported: isNoiseFilterSupported(),
  create: () => new NoiseFilterProcessor(),
  onUnavailable: (e) => console.warn('Noise reduction is not available, using the plain microphone:', e),
  onUserGesture: (retry) => {
    const once = () => {
      off();
      retry();
    };
    const off = () => {
      document.removeEventListener('pointerdown', once, true);
      document.removeEventListener('keydown', once, true);
    };
    document.addEventListener('pointerdown', once, true);
    document.addEventListener('keydown', once, true);
    return off;
  },
});

/** A processor change in progress per track: the next change waits for it, so on/off toggles never overlap. */
const busy = new WeakMap<LocalAudioTrack, Promise<void>>();

/**
 * Keeps the noise filter on (or off) this room's microphone, now and for every microphone track published later
 * (a reconnect, or turning the microphone on). Device switches keep it: LiveKit feeds the new device into the same
 * processor. Never blocks the microphone: if the filter cannot start, the plain microphone is used. Returns the
 * cleanup.
 */
export function syncNoiseFilter(room: Room, enabled: boolean, deps: NoiseFilterDeps = defaultDeps()): () => void {
  let disposed = false;
  let stopWaiting: (() => void) | null = null;

  const change = async (track: LocalAudioTrack) => {
    const ours = track.getProcessor()?.name === NOISE_FILTER_NAME;
    if (!enabled) {
      if (ours) await track.stopProcessor();
      return;
    }
    if (ours || !deps.supported || disposed) return;
    try {
      await track.setProcessor(deps.create());
    } catch (e) {
      if (e instanceof AudioBlockedError) {
        stopWaiting ??= deps.onUserGesture(() => {
          stopWaiting = null;
          const now = micTrack();
          if (now && !disposed) apply(now);
        });
      } else {
        deps.onUnavailable(e);
      }
    }
  };
  const apply = (track: LocalAudioTrack) => {
    const next = (busy.get(track) ?? Promise.resolve()).then(() => change(track)).catch(deps.onUnavailable);
    busy.set(track, next);
  };
  const micTrack = () => room.localParticipant.getTrackPublication(Track.Source.Microphone)?.audioTrack as LocalAudioTrack | undefined;
  const onPublished = (pub: LocalTrackPublication) => {
    if (pub.source === Track.Source.Microphone && pub.audioTrack) apply(pub.audioTrack as LocalAudioTrack);
  };

  room.on(RoomEvent.LocalTrackPublished, onPublished);
  const existing = micTrack();
  if (existing) apply(existing);
  return () => {
    disposed = true;
    room.off(RoomEvent.LocalTrackPublished, onPublished);
    stopWaiting?.();
  };
}

/** Noise reduction for the microphone this user publishes in `room` (see syncNoiseFilter). */
export function useNoiseFilter(room: Room | null | undefined, enabled: boolean): void {
  useEffect(() => (room ? syncNoiseFilter(room, enabled) : undefined), [room, enabled]);
}
