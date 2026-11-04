import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AudioProcessorOptions } from 'livekit-client';

const lib = vi.hoisted(() => ({
  loadRnnoise: vi.fn(async () => new ArrayBuffer(8)),
  created: [] as { options: unknown; destroy: ReturnType<typeof vi.fn> }[],
}));
vi.mock('@sapphi-red/web-noise-suppressor', () => ({
  loadRnnoise: lib.loadRnnoise,
  RnnoiseWorkletNode: class {
    destroy = vi.fn();
    connect = vi.fn((n: unknown) => n);
    constructor(_ctx: unknown, options: unknown) {
      lib.created.push({ options, destroy: this.destroy });
    }
  },
}));

import {
  AudioBlockedError,
  LIMITER,
  LOW_CUT_HZ,
  NoiseFilterProcessor,
  NoiseFilterTimeoutError,
  SAMPLE_RATE,
  START_TIMEOUT_MS,
  isNoiseFilterSupported,
} from './noiseFilter';

/** A minimal Web Audio stand-in that records the graph. */
class FakeContext {
  static last: FakeContext;
  state: AudioContextState;
  closed = false;
  sources: { track: MediaStreamTrack; connect: ReturnType<typeof vi.fn>; disconnect: ReturnType<typeof vi.fn> }[] = [];
  out = { channelCount: 2, stream: { getAudioTracks: () => [this.processed] } };
  processed = { stop: vi.fn(), kind: 'audio' } as unknown as MediaStreamTrack;
  audioWorklet = { addModule: vi.fn(async () => undefined) };
  constructor(public options: AudioContextOptions) {
    FakeContext.last = this;
    this.state = FakeContext.startState;
  }
  static startState: AudioContextState = 'running';
  static resumable = true;
  resume = vi.fn(() => {
    if (FakeContext.resumable) this.state = 'running';
    return FakeContext.resumable ? Promise.resolve() : new Promise<void>(() => undefined);
  });
  close = vi.fn(async () => {
    this.closed = true;
  });
  createMediaStreamDestination() {
    return this.out;
  }
  createMediaStreamSource(stream: { tracks: MediaStreamTrack[] }) {
    const s = { track: stream.tracks[0]!, connect: vi.fn(), disconnect: vi.fn() };
    this.sources.push(s);
    return s;
  }
}
const nodes: { type: string; options: unknown }[] = [];
class FakeNode {
  constructor(
    public type: string,
    options: unknown,
  ) {
    nodes.push({ type, options });
  }
  connect = vi.fn((n: unknown) => n);
}

const opts = (track: MediaStreamTrack) => ({ kind: 'audio', track, audioContext: {} }) as unknown as AudioProcessorOptions;
const track = (id: string) => ({ id, kind: 'audio' }) as unknown as MediaStreamTrack;

beforeEach(() => {
  FakeContext.startState = 'running';
  FakeContext.resumable = true;
  nodes.length = 0;
  lib.created.length = 0;
  vi.stubGlobal('AudioContext', FakeContext);
  vi.stubGlobal('MediaStream', class {
    constructor(public tracks: MediaStreamTrack[]) {}
  });
  vi.stubGlobal('BiquadFilterNode', class extends FakeNode {
    constructor(_c: unknown, o: unknown) {
      super('lowcut', o);
    }
  });
  vi.stubGlobal('DynamicsCompressorNode', class extends FakeNode {
    constructor(_c: unknown, o: unknown) {
      super('limiter', o);
    }
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('isNoiseFilterSupported', () => {
  const full = {
    AudioContext: Object.assign(function () {}, { prototype: { createMediaStreamDestination() {} } }),
    AudioWorkletNode: function () {},
    WebAssembly: {},
    MediaStream: function () {},
  } as unknown as Partial<typeof globalThis>;
  it('needs AudioWorklet, WebAssembly and Web Audio media streams', () => {
    expect(isNoiseFilterSupported(full)).toBe(true);
    expect(isNoiseFilterSupported({ ...full, AudioWorkletNode: undefined })).toBe(false);
    expect(isNoiseFilterSupported({ ...full, WebAssembly: undefined })).toBe(false);
    expect(isNoiseFilterSupported({ ...full, AudioContext: undefined })).toBe(false);
    expect(isNoiseFilterSupported({})).toBe(false);
  });
  it('is false in this test environment (jsdom has no AudioWorklet), so the UI hides the toggle there', () => {
    vi.unstubAllGlobals();
    expect(isNoiseFilterSupported()).toBe(false);
  });
});

describe('NoiseFilterProcessor', () => {
  it('builds mic → low-cut → RNNoise → limiter → mono output at 48 kHz', async () => {
    const p = new NoiseFilterProcessor();
    const mic = track('mic-1');
    await p.init(opts(mic));
    const ctx = FakeContext.last;
    expect(ctx.options.sampleRate).toBe(SAMPLE_RATE);
    expect(ctx.audioWorklet.addModule).toHaveBeenCalledTimes(1);
    expect(nodes.map((n) => n.type)).toEqual(['lowcut', 'limiter']);
    expect(nodes[0]!.options).toMatchObject({ type: 'highpass', frequency: LOW_CUT_HZ });
    // Mixed down to mono before RNNoise, which cleans one channel only (otherwise the output is 6 dB quieter).
    expect(nodes[0]!.options).toMatchObject({ channelCount: 1, channelCountMode: 'explicit', channelInterpretation: 'speakers' });
    expect(nodes[1]!.options).toEqual(LIMITER);
    expect(lib.created[0]!.options).toMatchObject({ maxChannels: 1 });
    expect(ctx.out.channelCount).toBe(1);
    expect(ctx.sources).toHaveLength(1);
    expect(ctx.sources[0]!.track).toBe(mic);
    expect(p.processedTrack).toBe(ctx.processed);
  });

  it('a new microphone track (device switch) is fed into the same chain and output track', async () => {
    const p = new NoiseFilterProcessor();
    await p.init(opts(track('mic-1')));
    const out = p.processedTrack;
    const ctx = FakeContext.last;
    const next = track('mic-2');
    await p.restart(opts(next));
    expect(ctx.sources[0]!.disconnect).toHaveBeenCalled();
    expect(ctx.sources[1]!.track).toBe(next);
    expect(p.processedTrack).toBe(out);
  });

  it('destroy releases the worklet, the output track and the audio context', async () => {
    const p = new NoiseFilterProcessor();
    await p.init(opts(track('mic-1')));
    const ctx = FakeContext.last;
    const out = p.processedTrack!;
    await p.destroy();
    expect(lib.created[0]!.destroy).toHaveBeenCalled();
    expect(out.stop).toHaveBeenCalled();
    expect(ctx.closed).toBe(true);
    expect(p.processedTrack).toBeUndefined();
  });

  it('resumes a suspended context when the browser allows it', async () => {
    FakeContext.startState = 'suspended';
    const p = new NoiseFilterProcessor();
    await p.init(opts(track('mic-1')));
    expect(FakeContext.last.resume).toHaveBeenCalled();
    expect(p.processedTrack).toBeDefined();
  });

  it('a context the browser keeps suspended fails with AudioBlockedError and is closed', async () => {
    vi.useFakeTimers();
    FakeContext.startState = 'suspended';
    FakeContext.resumable = false;
    const p = new NoiseFilterProcessor();
    const done = p.init(opts(track('mic-1')));
    const failed = expect(done).rejects.toBeInstanceOf(AudioBlockedError);
    await vi.runAllTimersAsync();
    await failed;
    expect(FakeContext.last.closed).toBe(true);
    expect(p.processedTrack).toBeUndefined();
  });

  it('a worklet that never loads gives up after START_TIMEOUT_MS (so device switches are not blocked)', async () => {
    vi.useFakeTimers();
    const p = new NoiseFilterProcessor();
    const done = p.init(opts(track('mic-1')));
    FakeContext.last.audioWorklet.addModule.mockReturnValue(new Promise<undefined>(() => undefined));
    const failed = expect(done).rejects.toBeInstanceOf(NoiseFilterTimeoutError);
    await vi.advanceTimersByTimeAsync(START_TIMEOUT_MS - 1);
    expect(FakeContext.last.closed).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await failed;
    expect(FakeContext.last.closed).toBe(true);
    expect(p.processedTrack).toBeUndefined();
  });

  it('a failed download of the filter closes the context and reports the error', async () => {
    // A fresh module: the binary is downloaded once per page and earlier tests already loaded it.
    vi.resetModules();
    const fresh = await import('./noiseFilter');
    lib.loadRnnoise.mockClear();
    lib.loadRnnoise.mockRejectedValueOnce(new Error('404'));
    const p = new fresh.NoiseFilterProcessor();
    await expect(p.init(opts(track('mic-1')))).rejects.toThrow('404');
    expect(FakeContext.last.closed).toBe(true);
    expect(p.processedTrack).toBeUndefined();
    // The next attempt downloads it again; later ones reuse it.
    await new fresh.NoiseFilterProcessor().init(opts(track('mic-1')));
    await new fresh.NoiseFilterProcessor().init(opts(track('mic-1')));
    expect(lib.loadRnnoise).toHaveBeenCalledTimes(2);
  });
});
