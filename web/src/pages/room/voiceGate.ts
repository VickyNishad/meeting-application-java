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

/** Root mean square of audio samples (-1..1). */
export function rms(samples: ArrayLike<number>): number {
  if (samples.length === 0) return 0;
  let sum = 0;
  for (let i = 0; i < samples.length; i++) sum += samples[i]! * samples[i]!;
  return Math.sqrt(sum / samples.length);
}

/** Adds up the time spent at voice level; confirmed once it reaches VOICE_CONFIRM_MS. */
export class VoiceTally {
  private loudMs = 0;

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
 * Measures the microphone track while the clip records. If the browser's audio engine is not running (e.g. not
 * allowed to start), the level cannot be measured and the clip is treated as voice, so real speech is never lost.
 */
export function measureVoice(track: MediaStreamTrack, context: AudioContext): VoiceGate {
  let decide: (heard: boolean) => void = () => undefined;
  const heard = new Promise<boolean>((resolve) => (decide = resolve));
  let decided = false;
  let timer: ReturnType<typeof setInterval> | undefined;
  let source: MediaStreamAudioSourceNode | undefined;
  const finish = (verdict: boolean) => {
    if (decided) return;
    decided = true;
    if (timer !== undefined) clearInterval(timer);
    source?.disconnect();
    decide(verdict);
  };
  try {
    if (context.state !== 'running') {
      finish(true);
      return { heard, end: () => undefined };
    }
    source = context.createMediaStreamSource(new MediaStream([track]));
    const analyser = context.createAnalyser();
    analyser.fftSize = 1024;
    source.connect(analyser);
    const samples = new Float32Array(analyser.fftSize);
    const tally = new VoiceTally();
    timer = setInterval(() => {
      analyser.getFloatTimeDomainData(samples);
      if (tally.add(rms(samples), VOICE_SAMPLE_MS)) finish(true);
    }, VOICE_SAMPLE_MS);
  } catch {
    finish(true);
  }
  return { heard, end: () => finish(false) };
}
