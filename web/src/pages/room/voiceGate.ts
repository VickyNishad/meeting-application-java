/**
 * Whether a speaking clip really holds a voice. The media server's speaking detection also reacts to a cough, a click
 * or a fan; a clip is only stored once its microphone has carried speech-level sound for a while, so a turn in which
 * nobody actually spoke leaves nothing on the server.
 */

/** RMS level (0..1 full scale) that counts as voice; about -36 dBFS, well above a noise-suppressed silent room. */
export const VOICE_RMS = 0.015;
/** Voice-level sound needed (in total, within the clip) before the clip is kept. */
export const VOICE_CONFIRM_MS = 300;
/** How often the level is measured. */
export const VOICE_SAMPLE_MS = 50;
/** How far back from the start of a clip its voice is counted: less than CLIP_RELEASE_MS, so not the previous turn. */
export const VOICE_LEAD_MS = 1200;
/** Most one measurement can stand for: a background tab measures about once a second, and one cough is not a voice. */
export const MAX_TICK_MS = 150;

/** Root mean square of audio samples (-1..1). */
export function rms(samples: ArrayLike<number>): number {
  if (samples.length === 0) return 0;
  let sum = 0;
  for (let i = 0; i < samples.length; i++) sum += samples[i]! * samples[i]!;
  return Math.sqrt(sum / samples.length);
}

/** Adds up the time spent at voice level; confirmed once it reaches VOICE_CONFIRM_MS. */
export class VoiceTally {
  private loudMs: number;

  /** @param leadMs voice already heard just before the clip */
  constructor(leadMs = 0) {
    this.loudMs = leadMs;
  }

  /** One measurement covering `ms`; returns whether voice is confirmed now. */
  add(level: number, ms: number): boolean {
    if (level >= VOICE_RMS) this.loudMs += ms;
    return this.confirmed;
  }

  get confirmed(): boolean {
    return this.loudMs >= VOICE_CONFIRM_MS;
  }
}

/**
 * The verdict for one clip: `heard` resolves true as soon as a voice is confirmed, or false when the clip ends
 * (`end`) without one.
 */
export interface VoiceGate {
  readonly heard: Promise<boolean>;
  end(): void;
}

/** Every clip counts as voice (no measurement possible). */
export const ALWAYS_HEARD: VoiceGate = { heard: Promise.resolve(true), end: () => undefined };

/**
 * The microphone's level over time, and the gates of the clips being recorded. A clip only starts once the media server
 * has reported speech, about 0.7 s into a turn, so a clip is also credited with the voice of the moments just before it:
 * otherwise a short phrase would be over before enough of it had been measured.
 */
export class VoiceWatch {
  private recent: { at: number; ms: number }[] = [];
  private lastTick: number | null = null;
  private readonly open = new Set<{ tally: VoiceTally; decide: (heard: boolean) => void }>();

  /** One measurement at time `now`. Timers run late in a background tab, so the real time passed is what counts. */
  tick(now: number, level: number): void {
    const ms = Math.min(this.lastTick === null ? VOICE_SAMPLE_MS : now - this.lastTick, MAX_TICK_MS);
    this.lastTick = now;
    if (level >= VOICE_RMS) this.recent.push({ at: now, ms });
    this.recent = this.recent.filter((e) => e.at > now - VOICE_LEAD_MS);
    for (const gate of [...this.open]) {
      if (gate.tally.add(level, ms)) {
        this.open.delete(gate);
        gate.decide(true);
      }
    }
  }

  /** A gate for a clip starting at time `now`. */
  gate(now: number): VoiceGate {
    const lead = this.recent.filter((e) => e.at > now - VOICE_LEAD_MS).reduce((sum, e) => sum + e.ms, 0);
    const tally = new VoiceTally(lead);
    if (tally.confirmed) return ALWAYS_HEARD;
    let decide: (heard: boolean) => void = () => undefined;
    const heard = new Promise<boolean>((resolve) => (decide = resolve));
    const entry = { tally, decide };
    this.open.add(entry);
    return {
      heard,
      end: () => {
        if (this.open.delete(entry)) decide(false);
      },
    };
  }
}

/**
 * Listens to a microphone track for as long as it is in use. If the browser's audio engine is not running (e.g. not
 * allowed to start), the level cannot be measured and every clip is treated as voice, so real speech is never lost.
 */
export class VoiceMonitor {
  private readonly watch = new VoiceWatch();
  private readonly context: AudioContext;
  private timer: ReturnType<typeof setInterval> | undefined;
  private source: MediaStreamAudioSourceNode | undefined;

  constructor(track: MediaStreamTrack, context: AudioContext) {
    this.context = context;
    if (context.state !== 'running') return;
    this.source = context.createMediaStreamSource(new MediaStream([track]));
    const analyser = context.createAnalyser();
    analyser.fftSize = 1024;
    this.source.connect(analyser);
    const samples = new Float32Array(analyser.fftSize);
    this.timer = setInterval(() => {
      analyser.getFloatTimeDomainData(samples);
      this.watch.tick(performance.now(), rms(samples));
    }, VOICE_SAMPLE_MS);
  }

  gate(): VoiceGate {
    return this.timer === undefined || this.context.state !== 'running' ? ALWAYS_HEARD : this.watch.gate(performance.now());
  }

  stop(): void {
    if (this.timer !== undefined) clearInterval(this.timer);
    this.timer = undefined;
    this.source?.disconnect();
  }
}
