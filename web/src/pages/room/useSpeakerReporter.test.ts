import { describe, expect, it } from 'vitest';
import { SpeakingTally } from './useSpeakerReporter';

const A = '11111111-1111-4111-8111-111111111111';
const B = '22222222-2222-4222-8222-222222222222';

describe('SpeakingTally', () => {
  it('counts speaking time and turns from active-speaker updates', () => {
    const t = new SpeakingTally();
    t.update([A], 0);
    t.update([A, B], 1000);
    t.update([B], 3000);
    t.update([], 4000);
    expect(t.drain(5000)).toEqual([
      { participantId: A, speakingMs: 3000, turns: 1 },
      { participantId: B, speakingMs: 3000, turns: 1 },
    ]);
    expect(t.drain(6000)).toEqual([]);
  });

  it('includes ongoing speech up to the drain and continues from there', () => {
    const t = new SpeakingTally();
    t.update([A], 0);
    expect(t.drain(4000)).toEqual([{ participantId: A, speakingMs: 4000, turns: 1 }]);
    expect(t.drain(6000)).toEqual([{ participantId: A, speakingMs: 2000, turns: 0 }]);
  });

  it('skips service participants such as the recorder and caps a batch at 30 s', () => {
    const t = new SpeakingTally();
    t.update(['EG_recorder', A], 0);
    expect(t.drain(120_000)).toEqual([{ participantId: A, speakingMs: 30_000, turns: 1 }]);
  });
});
