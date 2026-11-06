import { describe, expect, it } from 'vitest';
import { CLIP_RELEASE_MS } from './useSpeechRecorder';
import { VOICE_CONFIRM_MS, VOICE_LEAD_MS, VOICE_RMS, VOICE_SAMPLE_MS, VoiceTally, VoiceWatch, rms } from './voiceGate';

const SPEECH = VOICE_RMS * 5;
const QUIET = 0.002;

/** Measures `level` every `every` ms for `ms`, from `from`; returns the time reached. */
function play(watch: VoiceWatch, from: number, ms: number, level: number, every = VOICE_SAMPLE_MS): number {
  let t = from;
  for (; t < from + ms; t += every) watch.tick(t, level);
  return t;
}

const settled = async (heard: Promise<boolean>) => Promise.race([heard, Promise.resolve('undecided' as const)]);

describe('voice level', () => {
  it('measures the RMS level of samples', () => {
    expect(rms([])).toBe(0);
    expect(rms([0, 0, 0])).toBe(0);
    expect(rms([0.5, -0.5, 0.5, -0.5])).toBeCloseTo(0.5);
  });

  it('confirms a voice only after enough voice-level time, not for a silent room or a blip', () => {
    const silent = new VoiceTally();
    for (let i = 0; i < 100; i++) silent.add(0.002, 50); // 5 s of a quiet, noise-suppressed room
    expect(silent.confirmed).toBe(false);

    const blip = new VoiceTally();
    blip.add(0.3, 50); // a click
    blip.add(0.3, 50);
    expect(blip.confirmed).toBe(false);

    const speech = new VoiceTally();
    let confirmed = false;
    for (let i = 0; i < VOICE_CONFIRM_MS / 50; i++) confirmed = speech.add(VOICE_RMS * 3, 50);
    expect(confirmed).toBe(true);
  });
});

describe('a short spoken turn', () => {
  it('is kept even though the clip starts when most of the turn is over', async () => {
    const watch = new VoiceWatch();
    let t = play(watch, 0, 2000, QUIET);
    // The media server reports speech 0.7 s into a 1 s phrase.
    t = play(watch, t, 700, SPEECH);
    const gate = watch.gate(t);
    play(watch, t, 300, SPEECH);
    gate.end();

    expect(await gate.heard).toBe(true);
  });

  it('is kept when the clip starts as the phrase ends', async () => {
    const watch = new VoiceWatch();
    const t = play(watch, 0, 500, SPEECH);
    const gate = watch.gate(t);
    gate.end();

    expect(await gate.heard).toBe(true);
  });

  it('is decided as soon as enough voice has been heard, without waiting for the clip to end', async () => {
    const watch = new VoiceWatch();
    let t = play(watch, 0, VOICE_CONFIRM_MS - 100, SPEECH);
    const gate = watch.gate(t);
    expect(await settled(gate.heard)).toBe('undecided');
    t = play(watch, t, 150, SPEECH);

    expect(await settled(gate.heard)).toBe(true);
  });
});

describe('what is still dropped', () => {
  it('a turn with no voice in it', async () => {
    const watch = new VoiceWatch();
    const t = play(watch, 0, 3000, QUIET);
    const gate = watch.gate(t);
    play(watch, t, 2000, QUIET);
    gate.end();

    expect(await gate.heard).toBe(false);
  });

  it('a short cough or click', async () => {
    const watch = new VoiceWatch();
    let t = play(watch, 0, 1000, QUIET);
    t = play(watch, t, 150, 0.4);
    const gate = watch.gate(t);
    play(watch, t, 1500, QUIET);
    gate.end();

    expect(await gate.heard).toBe(false);
  });

  it('noise right after someone finished speaking: their words do not count for it', async () => {
    const watch = new VoiceWatch();
    let t = play(watch, 0, 3000, SPEECH);
    t = play(watch, t, CLIP_RELEASE_MS, QUIET); // the previous turn has ended
    const gate = watch.gate(t);
    play(watch, t, 1000, QUIET);
    gate.end();

    expect(await gate.heard).toBe(false);
  });

  it('never looks back as far as the previous turn', () => {
    expect(VOICE_LEAD_MS).toBeLessThan(CLIP_RELEASE_MS);
  });
});

describe('a background tab, where the browser slows timers to about once a second', () => {
  it('keeps a spoken turn', async () => {
    const watch = new VoiceWatch();
    const gate = watch.gate(0);
    play(watch, 0, 3000, SPEECH, 1000);
    gate.end();

    expect(await gate.heard).toBe(true);
  });

  it('does not let one loud reading stand for a whole second of voice', async () => {
    const watch = new VoiceWatch();
    let t = play(watch, 0, 3000, QUIET, 1000);
    const gate = watch.gate(t);
    watch.tick(t + 1000, 0.4);
    t = play(watch, t + 2000, 2000, QUIET, 1000);
    gate.end();

    expect(await gate.heard).toBe(false);
  });
});

describe('a gate that has ended', () => {
  it('ignores later sound', async () => {
    const watch = new VoiceWatch();
    const gate = watch.gate(0);
    gate.end();
    play(watch, 0, 1000, SPEECH);

    expect(await gate.heard).toBe(false);
  });
});
