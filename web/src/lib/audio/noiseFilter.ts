import type { AudioProcessorOptions, Track, TrackProcessor } from 'livekit-client';
import rnnoiseWorkletUrl from '@sapphi-red/web-noise-suppressor/rnnoiseWorklet.js?url';
import rnnoiseWasmUrl from '@sapphi-red/web-noise-suppressor/rnnoise.wasm?url';
import rnnoiseSimdWasmUrl from '@sapphi-red/web-noise-suppressor/rnnoise_simd.wasm?url';

/**
 * Microphone noise reduction: low-cut filter → RNNoise (a small speech-trained noise suppressor, run in the browser
 * as WebAssembly in an AudioWorklet) → peak limiter. Runs on top of the browser's own echo cancellation, noise
 * suppression and gain control, which stay on: RNNoise removes what those leave behind (fan, AC, keyboard, traffic,
 * voices in the background), the low-cut removes rumble and hum, and the limiter stops loud peaks from clipping.
 *
 * Installed as a LiveKit track processor, so everything that takes the microphone gets the cleaned audio: the
 * meeting itself, the server's speaking detection, cloud recordings and the speaking clips recorded in the browser
 * (LocalTrack.mediaStreamTrack returns the processed track).
 */

/** RNNoise works on 48 kHz audio only. */
export const SAMPLE_RATE = 48_000;
/** Below the lowest voice fundamentals (~85 Hz): removes rumble, AC/fan hum and desk thumps, keeps the voice. */
export const LOW_CUT_HZ = 80;
/**
 * Limiter: peaks above the threshold are held there (with the compressor's automatic make-up gain they end ~1 dB
 * below full scale); normal speech, well below it, passes untouched.
 */
export const LIMITER = { threshold: -2, knee: 0, ratio: 20, attack: 0.002, release: 0.1 } as const;
/** How long to wait for the browser to allow audio processing before falling back to the plain microphone. */
export const RESUME_TIMEOUT_MS = 1500;
/**
 * The longest the filter may take to start (download, worklet, WebAssembly). LiveKit holds the microphone track's
 * lock while a processor starts, so a start that never finishes (seen where the audio worklet thread cannot run)
 * would also block device switches: give up instead and keep the plain microphone.
 */
export const START_TIMEOUT_MS = 8000;
export const NOISE_FILTER_NAME = 'meetly-noise-filter';

/** The browser does not let the page process audio yet (no user interaction since the page was loaded). */
export class AudioBlockedError extends Error {
  constructor() {
    super('The browser has not allowed audio processing yet.');
    this.name = 'AudioBlockedError';
  }
}

/** Whether this browser can run the noise filter (AudioWorklet, WebAssembly, Web Audio media streams). */
export function isNoiseFilterSupported(w: Partial<typeof globalThis> = globalThis): boolean {
  return (
    typeof w.AudioContext === 'function' &&
    typeof w.AudioWorkletNode === 'function' &&
    typeof w.WebAssembly === 'object' &&
    typeof w.MediaStream === 'function' &&
    !!w.AudioContext.prototype &&
    'createMediaStreamDestination' in w.AudioContext.prototype
  );
}

let wasm: Promise<ArrayBuffer> | null = null;
/** The RNNoise binary, downloaded once per page (the SIMD build where the browser supports it). */
function rnnoiseWasm(load: (o: { url: string; simdUrl: string }) => Promise<ArrayBuffer>): Promise<ArrayBuffer> {
  wasm ??= load({ url: rnnoiseWasmUrl, simdUrl: rnnoiseSimdWasmUrl }).catch((e: unknown) => {
    wasm = null; // let a later attempt download it again
    throw e;
  });
  return wasm;
}

/** The filter did not start within START_TIMEOUT_MS. */
export class NoiseFilterTimeoutError extends Error {
  constructor() {
    super(`The noise filter did not start within ${START_TIMEOUT_MS / 1000} s.`);
    this.name = 'NoiseFilterTimeoutError';
  }
}

/** Settles like `p`, or rejects with `error()` once `ms` have passed. */
function deadline<T>(p: Promise<T>, ms: number, error: () => Error): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const expired = new Promise<never>((_, reject) => (timer = setTimeout(() => reject(error()), ms)));
  return Promise.race([p, expired]).finally(() => clearTimeout(timer));
}

/** Loads the RNNoise library, binary and worklet into `ctx`. */
async function loadRnnoise(ctx: AudioContext) {
  // Imported only here: the library defines AudioWorkletNode subclasses when it loads.
  const lib = await import('@sapphi-red/web-noise-suppressor');
  const [binary] = await Promise.all([rnnoiseWasm(lib.loadRnnoise), ctx.audioWorklet.addModule(rnnoiseWorkletUrl)]);
  return { RnnoiseWorkletNode: lib.RnnoiseWorkletNode, binary };
}

async function resumed(ctx: AudioContext): Promise<void> {
  if (ctx.state === 'running') return;
  let timer: ReturnType<typeof setTimeout> | undefined;
  await Promise.race([ctx.resume().catch(() => undefined), new Promise((r) => (timer = setTimeout(r, RESUME_TIMEOUT_MS)))]);
  clearTimeout(timer);
  if ((ctx.state as AudioContextState) !== 'running') throw new AudioBlockedError();
}

export class NoiseFilterProcessor implements TrackProcessor<Track.Kind.Audio, AudioProcessorOptions> {
  readonly name = NOISE_FILTER_NAME;
  processedTrack?: MediaStreamTrack;
  private ctx?: AudioContext;
  private source?: MediaStreamAudioSourceNode;
  private lowCut?: BiquadFilterNode;
  private rnnoise?: AudioWorkletNode & { destroy(): void };
  private limiter?: DynamicsCompressorNode;

  async init({ track }: AudioProcessorOptions): Promise<void> {
    // Its own 48 kHz context: LiveKit's shared context runs at the device rate, which RNNoise cannot use.
    const ctx = new AudioContext({ sampleRate: SAMPLE_RATE, latencyHint: 'interactive' });
    try {
      await resumed(ctx);
      const { RnnoiseWorkletNode, binary } = await deadline(loadRnnoise(ctx), START_TIMEOUT_MS, () => new NoiseFilterTimeoutError());

      // Mono from the first node on: a microphone source can deliver its one channel as two identical ones, and RNNoise
      // (maxChannels 1) only cleans channel 0, so the mono output would average it with a silent channel (-6 dB).
      this.lowCut = new BiquadFilterNode(ctx, {
        type: 'highpass',
        frequency: LOW_CUT_HZ,
        Q: Math.SQRT1_2,
        channelCount: 1,
        channelCountMode: 'explicit',
        channelInterpretation: 'speakers',
      });
      this.rnnoise = new RnnoiseWorkletNode(ctx, { wasmBinary: binary, maxChannels: 1 });
      this.limiter = new DynamicsCompressorNode(ctx, LIMITER);
      const out = ctx.createMediaStreamDestination();
      out.channelCount = 1;
      this.lowCut.connect(this.rnnoise).connect(this.limiter).connect(out);
      this.ctx = ctx;
      this.connect(track);
      this.processedTrack = out.stream.getAudioTracks()[0];
    } catch (e) {
      this.ctx = undefined;
      await ctx.close().catch(() => undefined);
      throw e;
    }
  }

  /** A new microphone track (device switch, unmute after a stopped track): feed it into the same chain. */
  async restart({ track }: AudioProcessorOptions): Promise<void> {
    this.connect(track);
  }

  async destroy(): Promise<void> {
    this.source?.disconnect();
    this.rnnoise?.destroy();
    this.processedTrack?.stop();
    this.processedTrack = undefined;
    const ctx = this.ctx;
    this.ctx = undefined;
    await ctx?.close().catch(() => undefined);
  }

  private connect(track: MediaStreamTrack): void {
    if (!this.ctx || !this.lowCut) return;
    this.source?.disconnect();
    this.source = this.ctx.createMediaStreamSource(new MediaStream([track]));
    this.source.connect(this.lowCut);
  }
}
