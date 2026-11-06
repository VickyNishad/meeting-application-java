import { useCallback, useEffect, useRef } from 'react';
import { Track, type Participant } from 'livekit-client';
import { speechRecordingsApi } from '@/lib/api/endpoints';
import { useSpeakingIndicator } from './speaking';
import { ALWAYS_HEARD, VoiceMonitor, type VoiceGate } from './voiceGate';

/**
 * A speaking turn ends after this much silence: shorter pauses stay in the same clip, longer ones start a new one.
 * Longer than the tile highlight's hold, so ordinary pauses between sentences do not split a turn.
 */
export const CLIP_RELEASE_MS = 1500;
/** The recorder hands over a chunk this often, so a clip is mostly on the server even if the browser dies. */
export const CHUNK_MS = 1000;
const UPLOAD_ATTEMPTS = 3;
/**
 * A new clip is opened on the server only once the previous one has been finished there (starting a clip closes any
 * clip of the same participant that is still open), but never waits longer than this for it.
 */
export const PREVIOUS_CLIP_WAIT_MS = 10_000;

export interface ClipApi {
  /** {@code startedMsAgo}: how long ago this clip began recording (it is asked for only once a voice was heard). */
  start(format: string, startedMsAgo: number): Promise<{ id: string }>;
  chunk(id: string, seq: number, data: Blob): Promise<unknown>;
  finish(id: string, chunks: number): Promise<unknown>;
}

export interface RecorderLike {
  start(timeslice: number): void;
  stop(): void;
  readonly state: string;
  ondataavailable: ((e: { data: Blob }) => void) | null;
  onstop: (() => void) | null;
}

/** The WebM type this browser can record for these tracks (video if a camera track is included). */
export function clipFormat(hasVideo: boolean, isSupported: (t: string) => boolean): string | null {
  const candidates = hasVideo ? ['video/webm;codecs=vp8,opus', 'video/webm'] : ['audio/webm;codecs=opus', 'audio/webm'];
  return candidates.find(isSupported) ?? null;
}

/**
 * One clip: records from the moment it is created (nothing is lost while the server reserves the file), asks the
 * server for the clip once the previous clip (`after`) has settled, uploads the chunks in order once the server
 * answered, retrying each a few times, and finishes with the number of chunks sent. If the server refuses the clip,
 * the recording stops and nothing is kept locally.
 */
export class ClipSession {
  private settle: () => void = () => undefined;
  /** Resolves once this clip is finished on the server, or was refused or given up. Never rejects. */
  readonly settled = new Promise<void>((resolve) => (this.settle = resolve));
  private id: string | null = null;
  private queue: Blob[] = [];
  private sent = 0;
  private uploading = false;
  private stopped = false;
  private finished = false;
  private refused = false;
  private recorderDone = false;
  private discarded = false;

  constructor(
    private readonly api: ClipApi,
    private readonly recorder: RecorderLike,
    format: string,
    private readonly onError: (e: unknown) => void,
    private readonly sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
    after: Promise<unknown> = Promise.resolve(),
    /** Whether the clip holds a voice: the server only hears of it once it does; otherwise it is dropped here. */
    private readonly voice: VoiceGate = ALWAYS_HEARD,
  ) {
    recorder.ondataavailable = (e) => {
      if (e.data && e.data.size > 0 && !this.refused) {
        this.queue.push(e.data);
        void this.pump();
      }
    };
    recorder.onstop = () => {
      this.recorderDone = true;
      void this.pump();
    };
    recorder.start(CHUNK_MS);
    const recordingSince = Date.now();
    let waited: ReturnType<typeof setTimeout> | undefined;
    const previous = Promise.race([
      after.catch(() => undefined),
      new Promise<void>((r) => (waited = setTimeout(r, PREVIOUS_CLIP_WAIT_MS))),
    ]).finally(() => clearTimeout(waited));
    // Recording runs from the start (nothing of the turn is lost); the server is asked for the clip only once a voice
    // was heard. A turn that ends without one (a cough, a click, background noise) is dropped and never stored.
    voice.heard.then((heard) => {
      if (!heard) {
        this.discarded = true;
        this.refused = true;
        this.queue = [];
        if (this.recorder.state !== 'inactive') this.recorder.stop();
        this.settle();
        return;
      }
      previous.then(() => this.api.start(format, Math.max(0, Date.now() - recordingSince))).then(
        (clip) => {
          this.id = clip.id;
          void this.pump();
        },
        (e) => {
          this.refused = true;
          this.queue = [];
          this.stop();
          this.settle();
          this.onError(e);
        },
      );
    });
  }

  /** Ends the clip (the recorder flushes its last chunk, then the clip is finished on the server). */
  stop(): void {
    if (this.stopped) return;
    this.stopped = true;
    if (this.recorder.state !== 'inactive') this.recorder.stop();
    else this.recorderDone = true;
    // No voice by now: the clip is dropped (see the constructor).
    this.voice.end();
    void this.pump();
  }

  /** Dropped without ever reaching the server: no voice was heard. */
  get wasDiscarded(): boolean {
    return this.discarded;
  }

  get done(): boolean {
    return this.finished || this.refused;
  }

  private async pump(): Promise<void> {
    if (this.uploading || this.id === null || this.refused) return;
    this.uploading = true;
    try {
      while (this.queue.length > 0) {
        const data = this.queue[0]!;
        await this.withRetry(() => this.api.chunk(this.id!, this.sent, data));
        this.queue.shift();
        this.sent++;
      }
      if (this.stopped && this.recorderDone && !this.finished) {
        this.finished = true;
        await this.withRetry(() => this.api.finish(this.id!, this.sent));
        this.settle();
      }
    } catch (e) {
      // The server closes a clip it no longer hears from (INCOMPLETE): nothing more to do here.
      this.refused = true;
      this.queue = [];
      if (this.recorder.state !== 'inactive') this.recorder.stop();
      this.settle();
      this.onError(e);
    } finally {
      this.uploading = false;
    }
  }

  private async withRetry<T>(fn: () => Promise<T>): Promise<T> {
    let last: unknown;
    for (let attempt = 0; attempt < UPLOAD_ATTEMPTS; attempt++) {
      try {
        return await fn();
      } catch (e) {
        last = e;
        if (attempt < UPLOAD_ATTEMPTS - 1) await this.sleep(500 * 2 ** attempt);
      }
    }
    throw last;
  }
}

/**
 * Records this participant's own speaking turns (microphone, plus camera when it is on) while `enabled` (a meeting
 * with automatic recording, where everyone was told before joining). A turn starts when the media server reports
 * this participant speaking and ends after CLIP_RELEASE_MS of silence, a mute, or leaving. Only this participant's own
 * tracks are ever recorded. A turn is stored only if the microphone carried a voice (see voiceGate.ts): a cough, a click
 * or background noise that the speaking detection reacted to leaves nothing. Note: speech detection lags the voice by a
 * few hundred milliseconds, so the very first
 * syllable of a turn can be missed.
 */
export function useSpeechRecorder(opts: {
  enabled: boolean;
  participant: Participant | null;
  meetingId: string;
  participantId: string;
  waitToken?: string;
  onError: (e: unknown) => void;
}) {
  const { enabled, participant, meetingId, participantId, waitToken } = opts;
  const speaking = useSpeakingIndicator(enabled && participant ? participant : undefined, CLIP_RELEASE_MS);
  const onErrorRef = useRef(opts.onError);
  onErrorRef.current = opts.onError;
  // The latest clip's `settled`: the next turn's clip waits for it before it is opened on the server.
  const previousClip = useRef<Promise<void>>(Promise.resolve());
  // One audio engine for measuring this participant's voice level, kept for the whole meeting.
  const audio = useRef<AudioContext | null>(null);
  // Listens to the microphone all the time, so that a clip can count the voice from just before it started.
  const monitor = useRef<{ track: MediaStreamTrack; voice: VoiceMonitor } | null>(null);
  useEffect(
    () => () => {
      monitor.current?.voice.stop();
      monitor.current = null;
      void audio.current?.close().catch(() => undefined);
      audio.current = null;
    },
    [],
  );
  const monitorOf = useCallback((track: MediaStreamTrack): VoiceMonitor => {
    if (monitor.current?.track === track) return monitor.current.voice;
    monitor.current?.voice.stop();
    audio.current ??= new AudioContext();
    if (audio.current.state === 'suspended') void audio.current.resume().catch(() => undefined);
    const voice = new VoiceMonitor(track, audio.current);
    monitor.current = { track, voice };
    return voice;
  }, []);
  const voiceGateFor = useCallback(
    (track: MediaStreamTrack): VoiceGate => {
      if (typeof AudioContext === 'undefined') return ALWAYS_HEARD;
      try {
        return monitorOf(track).gate();
      } catch {
        return ALWAYS_HEARD;
      }
    },
    [monitorOf],
  );

  // The microphone track can change during a meeting (another device, or turned off and on): follow it.
  useEffect(() => {
    if (!enabled || !participant || typeof AudioContext === 'undefined') return;
    const follow = () => {
      const track = participant.getTrackPublication(Track.Source.Microphone)?.track?.mediaStreamTrack;
      try {
        if (track) monitorOf(track);
      } catch {
        // Not measurable: clips are then treated as voice.
      }
    };
    follow();
    const timer = setInterval(follow, 1000);
    return () => clearInterval(timer);
  }, [enabled, participant, monitorOf]);

  useEffect(() => {
    if (!enabled || !speaking || !participant || typeof MediaRecorder === 'undefined') return;
    const mic = participant.getTrackPublication(Track.Source.Microphone);
    const micTrack = mic?.track?.mediaStreamTrack;
    if (!micTrack || mic?.isMuted) return;
    const cam = participant.getTrackPublication(Track.Source.Camera);
    const camTrack = cam && !cam.isMuted ? cam.track?.mediaStreamTrack : undefined;
    const format = clipFormat(!!camTrack, (t) => MediaRecorder.isTypeSupported(t));
    if (!format) return;
    const stream = new MediaStream(camTrack ? [micTrack, camTrack] : [micTrack]);
    let session: ClipSession;
    try {
      const recorder = new MediaRecorder(stream, { mimeType: format });
      session = new ClipSession(
        {
          start: (f, ago) => speechRecordingsApi.start(meetingId, participantId, f, waitToken, ago),
          chunk: (id, seq, data) => speechRecordingsApi.chunk(meetingId, id, seq, participantId, data, waitToken),
          finish: (id, chunks) => speechRecordingsApi.finish(meetingId, id, participantId, chunks, waitToken),
        },
        recorder as unknown as RecorderLike,
        format,
        (e) => onErrorRef.current(e),
        undefined,
        previousClip.current,
        voiceGateFor(micTrack),
      );
      previousClip.current = session.settled;
    } catch (e) {
      onErrorRef.current(e);
      return;
    }
    // Speech ended (after the release time), recording was switched off, or the participant left: end the clip.
    return () => session.stop();
  }, [enabled, speaking, participant, meetingId, participantId, waitToken, voiceGateFor]);
}
