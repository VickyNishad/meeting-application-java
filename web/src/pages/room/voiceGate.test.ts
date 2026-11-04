import { describe, expect, it } from 'vitest';
import { VOICE_CONFIRM_MS, VOICE_RMS, VoiceTally, rms } from './voiceGate';

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
