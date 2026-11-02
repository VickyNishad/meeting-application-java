import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, renderHook } from '@testing-library/react';
import { ConnectionQuality, ParticipantEvent, Track, type Participant } from 'livekit-client';
import { FRESH_REPORT_MS, SPEAKING_RELEASE_MS, releaseMsFrom, SpeakingHold, isSpeakingNow, useClearSpeakingOnReconnect, useSpeakingIndicator } from './speaking';
import { RoomEvent, type Room } from 'livekit-client';

/** A participant as the media server reports it: speaking state, microphone state, connection quality, events. */
class FakeParticipant {
  isSpeaking = false;
  isMicrophoneEnabled = true;
  connectionQuality = ConnectionQuality.Good;
  private listeners = new Map<string, Set<(...a: unknown[]) => void>>();
  constructor(public identity: string) {}
  on(e: string, f: (...a: unknown[]) => void) {
    if (!this.listeners.has(e)) this.listeners.set(e, new Set());
    this.listeners.get(e)!.add(f);
    return this;
  }
  off(e: string, f: (...a: unknown[]) => void) {
    this.listeners.get(e)?.delete(f);
    return this;
  }
  listenerCount() {
    return [...this.listeners.values()].reduce((n, s) => n + s.size, 0);
  }
  emit(e: string, ...a: unknown[]) {
    for (const f of [...(this.listeners.get(e) ?? [])]) f(...a);
  }
  /** The SFU's speaker update. */
  speak(on: boolean) {
    this.isSpeaking = on;
    this.emit(ParticipantEvent.IsSpeakingChanged, on);
  }
  mute() {
    this.isMicrophoneEnabled = false;
    this.emit(ParticipantEvent.TrackMuted, { source: Track.Source.Microphone });
  }
  unmute() {
    this.isMicrophoneEnabled = true;
    this.emit(ParticipantEvent.TrackUnmuted, { source: Track.Source.Microphone });
  }
  /** The SDK's setter (used when a room reconnects): emits only on a change. */
  setIsSpeaking(on: boolean) {
    if (on === this.isSpeaking) return;
    this.speak(on);
  }
  lose() {
    this.connectionQuality = ConnectionQuality.Lost;
    this.emit(ParticipantEvent.ConnectionQualityChanged, ConnectionQuality.Lost);
  }
}
const asParticipant = (p: FakeParticipant) => p as unknown as Participant;

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('isSpeakingNow', () => {
  it('follows the media server\'s voice activity, not the microphone switch', () => {
    const base = { isSpeaking: false, isMicrophoneEnabled: true, connectionQuality: ConnectionQuality.Good };
    expect(isSpeakingNow(base)).toBe(false); // microphone on, silent (or noise below the threshold)
    expect(isSpeakingNow({ ...base, isSpeaking: true })).toBe(true);
    expect(isSpeakingNow({ ...base, isSpeaking: true, isMicrophoneEnabled: false })).toBe(false); // stale report after a mute
    expect(isSpeakingNow({ ...base, isSpeaking: true, connectionQuality: ConnectionQuality.Lost })).toBe(false);
  });
});

describe('SpeakingHold (silence threshold)', () => {
  it('turns on at once and off only after the silence threshold', () => {
    const changes: boolean[] = [];
    const hold = new SpeakingHold(600, (v) => changes.push(v));
    hold.update(true);
    expect(changes).toEqual([true]);
    hold.update(false);
    vi.advanceTimersByTime(599);
    expect(changes).toEqual([true]);
    vi.advanceTimersByTime(1);
    expect(changes).toEqual([true, false]);
  });

  it('rapid start/stop within the threshold never flickers', () => {
    const changes: boolean[] = [];
    const hold = new SpeakingHold(600, (v) => changes.push(v));
    for (let i = 0; i < 20; i++) {
      hold.update(true);
      vi.advanceTimersByTime(100);
      hold.update(false);
      vi.advanceTimersByTime(300);
    }
    expect(changes).toEqual([true]);
    vi.advanceTimersByTime(600);
    expect(changes).toEqual([true, false]);
  });

  it('a hard stop skips the wait; dispose cancels a pending release', () => {
    const changes: boolean[] = [];
    const hold = new SpeakingHold(600, (v) => changes.push(v));
    hold.update(true);
    hold.update(false, true);
    expect(changes).toEqual([true, false]);
    hold.update(true);
    hold.update(false);
    hold.dispose();
    vi.advanceTimersByTime(5000);
    expect(changes).toEqual([true, false, true]);
  });

  it('repeated identical reports are not reported again', () => {
    const changes: boolean[] = [];
    const hold = new SpeakingHold(600, (v) => changes.push(v));
    hold.update(false);
    hold.update(false);
    hold.update(true);
    hold.update(true);
    expect(changes).toEqual([true]);
  });
});

describe('useSpeakingIndicator', () => {
  it('one participant speaking', () => {
    const p = new FakeParticipant('a');
    const { result } = renderHook(() => useSpeakingIndicator(asParticipant(p)));
    expect(result.current).toBe(false);
    act(() => p.speak(true));
    expect(result.current).toBe(true);
    act(() => p.speak(false));
    expect(result.current).toBe(true); // within the silence threshold
    act(() => vi.advanceTimersByTime(SPEAKING_RELEASE_MS));
    expect(result.current).toBe(false);
  });

  it('two participants speaking at the same time are both highlighted', () => {
    const a = new FakeParticipant('a');
    const b = new FakeParticipant('b');
    const ha = renderHook(() => useSpeakingIndicator(asParticipant(a)));
    const hb = renderHook(() => useSpeakingIndicator(asParticipant(b)));
    act(() => {
      a.speak(true);
      b.speak(true);
    });
    expect(ha.result.current).toBe(true);
    expect(hb.result.current).toBe(true);
  });

  it('microphone on without speaking is not highlighted', () => {
    const p = new FakeParticipant('a');
    const { result } = renderHook(() => useSpeakingIndicator(asParticipant(p)));
    act(() => p.emit(ParticipantEvent.TrackUnmuted, { source: Track.Source.Microphone }));
    act(() => vi.advanceTimersByTime(5000));
    expect(result.current).toBe(false);
  });

  it('microphone off ends the highlight at once, and a stale "speaking" report cannot bring it back', () => {
    const p = new FakeParticipant('a');
    const { result } = renderHook(() => useSpeakingIndicator(asParticipant(p)));
    act(() => p.speak(true));
    act(() => p.mute());
    expect(result.current).toBe(false);
    act(() => p.emit(ParticipantEvent.IsSpeakingChanged, true)); // isSpeaking still true from before the mute
    expect(result.current).toBe(false);
  });

  it('a lost connection (network interruption) ends the highlight at once', () => {
    const p = new FakeParticipant('a');
    const { result } = renderHook(() => useSpeakingIndicator(asParticipant(p)));
    act(() => p.speak(true));
    act(() => p.lose());
    expect(result.current).toBe(false);
  });

  it('a participant leaving while speaking: listeners and timers are cleaned up', () => {
    const p = new FakeParticipant('a');
    const { result, unmount } = renderHook(() => useSpeakingIndicator(asParticipant(p)));
    act(() => p.speak(true));
    act(() => p.speak(false)); // release timer pending
    expect(result.current).toBe(true);
    unmount();
    expect(p.listenerCount()).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('screen-share tiles (no participant given) are never highlighted', () => {
    const { result } = renderHook(() => useSpeakingIndicator(undefined));
    expect(result.current).toBe(false);
  });

  it('large room: one speaker re-renders only its own tile', () => {
    const people = Array.from({ length: 300 }, (_, i) => new FakeParticipant(`p${i}`));
    const renders = new Map<string, number>();
    function Tile({ p }: { p: FakeParticipant }) {
      useSpeakingIndicator(asParticipant(p));
      renders.set(p.identity, (renders.get(p.identity) ?? 0) + 1);
      return null;
    }
    render(
      <>
        {people.map((p) => (
          <Tile key={p.identity} p={p} />
        ))}
      </>,
    );
    const before = new Map(renders);
    const start = performance.now();
    act(() => people[42].speak(true));
    const elapsed = performance.now() - start;
    const rerendered = people.filter((p) => renders.get(p.identity) !== before.get(p.identity)).map((p) => p.identity);
    expect(rerendered).toEqual(['p42']);
    expect(elapsed).toBeLessThan(250);
  });
});

describe('QA fixes (SP-3, SP-4, SP-7, SP-8)', () => {
  it('SP-8: after an unmute the stale "speaking" report is ignored until a fresh one (or the fallback time)', () => {
    const p = new FakeParticipant('a');
    const { result } = renderHook(() => useSpeakingIndicator(asParticipant(p)));
    act(() => p.speak(true));
    act(() => p.mute());
    expect(result.current).toBe(false);
    act(() => p.unmute()); // isSpeaking is still true from before the mute
    expect(result.current).toBe(false);
    act(() => p.emit(ParticipantEvent.IsSpeakingChanged, true)); // a fresh report from the server
    expect(result.current).toBe(true);

    act(() => p.mute());
    act(() => p.unmute());
    expect(result.current).toBe(false);
    act(() => vi.advanceTimersByTime(FRESH_REPORT_MS)); // no report came: the current value is trusted again
    expect(result.current).toBe(true);
  });

  it("SP-7: a tile that switches to another participant shows that participant's state on its first render", () => {
    const a = new FakeParticipant('a');
    const b = new FakeParticipant('b');
    a.isSpeaking = true;
    const seen: boolean[] = [];
    const { rerender } = renderHook(({ p }) => {
      const v = useSpeakingIndicator(asParticipant(p));
      seen.push(v);
      return v;
    }, { initialProps: { p: a } });
    expect(seen.at(-1)).toBe(true);
    seen.length = 0;
    rerender({ p: b });
    // Committed state after the layout effect: b is silent; no hold carried over from a.
    expect(seen.at(-1)).toBe(false);
    b.isSpeaking = true;
    seen.length = 0;
    rerender({ p: a });
    expect(seen.at(-1)).toBe(true);
  });

  it("SP-3/SP-4: a room that reconnects (any room: stage or audience) clears everyone's speaking state", () => {
    const listeners = new Map<string, Set<() => void>>();
    const local = new FakeParticipant('me');
    const remote = new FakeParticipant('them');
    const room = {
      localParticipant: local,
      remoteParticipants: new Map([['them', remote]]),
      on: (e: string, f: () => void) => listeners.set(e, new Set([...(listeners.get(e) ?? []), f])),
      off: (e: string, f: () => void) => listeners.get(e)?.delete(f),
    } as unknown as Room;
    const emitRoom = (e: string) => listeners.get(e)?.forEach((f) => f());
    renderHook(() => useClearSpeakingOnReconnect([room]));
    const mine = renderHook(() => useSpeakingIndicator(asParticipant(local)));
    act(() => {
      local.speak(true);
      remote.speak(true);
    });
    expect(mine.result.current).toBe(true);
    for (const e of [RoomEvent.Reconnecting, RoomEvent.Reconnected, RoomEvent.SignalReconnecting, RoomEvent.Disconnected]) {
      act(() => {
        local.speak(true);
        remote.speak(true);
      });
      act(() => emitRoom(e));
      expect([e, local.isSpeaking, remote.isSpeaking]).toEqual([e, false, false]);
    }
    act(() => vi.advanceTimersByTime(SPEAKING_RELEASE_MS));
    expect(mine.result.current).toBe(false);
    // After the reconnect, a fresh "speaking" report shows again (the setter emits because the value was reset).
    act(() => local.setIsSpeaking(true));
    expect(mine.result.current).toBe(true);
  });
});

describe("routine events during a pause (found by the live speaking-clip test)", () => {
  it("a connection-quality update or track event in a pause between words does not cut the turn short", () => {
    const p = new FakeParticipant("a");
    const { result } = renderHook(() => useSpeakingIndicator(asParticipant(p)));
    act(() => p.speak(true));
    act(() => p.speak(false)); // a pause between words
    act(() => p.emit(ParticipantEvent.ConnectionQualityChanged, ConnectionQuality.Excellent));
    act(() => p.emit(ParticipantEvent.TrackSubscribed, { source: Track.Source.Microphone }));
    expect(result.current).toBe(true); // still within the silence threshold
    act(() => p.speak(true));
    act(() => vi.advanceTimersByTime(SPEAKING_RELEASE_MS * 2));
    expect(result.current).toBe(true);
  });
});

describe('configurable silence threshold (VITE_SPEAKING_RELEASE_MS)', () => {
  it('defaults to 600 ms when unset (the build used for these tests sets nothing)', () => {
    expect(SPEAKING_RELEASE_MS).toBe(600);
    expect(releaseMsFrom(undefined)).toBe(600);
    expect(releaseMsFrom('')).toBe(600);
  });

  it('accepts whole milliseconds from 0 to 10000 and falls back on anything else', () => {
    expect(releaseMsFrom('1200')).toBe(1200);
    expect(releaseMsFrom(' 0 ')).toBe(0);
    expect(releaseMsFrom('10000')).toBe(10000);
    for (const bad of ['10001', '-5', '1.5', 'abc', '600ms']) expect(releaseMsFrom(bad)).toBe(600);
  });

  it('a custom threshold is what the tile waits before removing the border', () => {
    vi.useFakeTimers();
    const shown: boolean[] = [];
    const hold = new SpeakingHold(releaseMsFrom('1500'), (s) => shown.push(s));
    hold.update(true);
    hold.update(false);
    vi.advanceTimersByTime(1499);
    expect(shown).toEqual([true]);
    vi.advanceTimersByTime(1);
    expect(shown).toEqual([true, false]);
    hold.dispose();
    vi.useRealTimers();
  });
});
