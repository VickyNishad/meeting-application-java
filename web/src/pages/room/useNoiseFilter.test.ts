import { describe, expect, it, vi } from 'vitest';
import { RoomEvent, Track, type Room } from 'livekit-client';
import { AudioBlockedError, NOISE_FILTER_NAME } from '@/lib/audio/noiseFilter';
import { syncNoiseFilter, type NoiseFilterDeps } from './useNoiseFilter';

type Processor = { name: string };

function fakeTrack(processor?: Processor) {
  const t = {
    processor,
    getProcessor: vi.fn(() => t.processor),
    setProcessor: vi.fn(async (p: Processor) => {
      t.processor = p;
    }),
    stopProcessor: vi.fn(async () => {
      t.processor = undefined;
    }),
  };
  return t;
}
type FakeTrack = ReturnType<typeof fakeTrack>;

function fakeRoom(mic?: FakeTrack) {
  const handlers = new Map<string, Set<(...a: unknown[]) => void>>();
  const pubs = new Map<Track.Source, { source: Track.Source; audioTrack?: FakeTrack }>();
  if (mic) pubs.set(Track.Source.Microphone, { source: Track.Source.Microphone, audioTrack: mic });
  const room = {
    localParticipant: { getTrackPublication: (s: Track.Source) => pubs.get(s) },
    on: (e: string, h: (...a: unknown[]) => void) => (handlers.get(e) ?? handlers.set(e, new Set()).get(e)!).add(h),
    off: (e: string, h: (...a: unknown[]) => void) => handlers.get(e)?.delete(h),
  };
  const publish = (source: Track.Source, audioTrack: FakeTrack) => {
    const pub = { source, audioTrack };
    pubs.set(source, pub);
    handlers.get(RoomEvent.LocalTrackPublished)?.forEach((h) => h(pub));
  };
  const listeners = () => handlers.get(RoomEvent.LocalTrackPublished)?.size ?? 0;
  return { room: room as unknown as Room, publish, listeners };
}

function deps(over: Partial<NoiseFilterDeps> = {}) {
  let gesture: (() => void) | null = null;
  const d = {
    supported: true,
    create: vi.fn(() => ({ name: NOISE_FILTER_NAME }) as never),
    onUnavailable: vi.fn(),
    onUserGesture: vi.fn((retry: () => void) => {
      gesture = retry;
      return () => (gesture = null);
    }),
    ...over,
  };
  return { d, gesture: () => gesture };
}

const settle = () => new Promise((r) => setTimeout(r, 0));

describe('syncNoiseFilter', () => {
  it('puts the filter on the microphone that is already published', async () => {
    const mic = fakeTrack();
    const { d } = deps();
    syncNoiseFilter(fakeRoom(mic).room, true, d);
    await settle();
    expect(mic.setProcessor).toHaveBeenCalledTimes(1);
    expect(mic.processor?.name).toBe(NOISE_FILTER_NAME);
  });

  it('puts it on microphones published later (reconnect, turning the mic on), not on other tracks', async () => {
    const r = fakeRoom();
    const { d } = deps();
    syncNoiseFilter(r.room, true, d);
    const cam = fakeTrack();
    r.publish(Track.Source.Camera, cam);
    const mic = fakeTrack();
    r.publish(Track.Source.Microphone, mic);
    await settle();
    expect(cam.setProcessor).not.toHaveBeenCalled();
    expect(mic.processor?.name).toBe(NOISE_FILTER_NAME);
  });

  it('does not stack a second filter on a track that has one', async () => {
    const mic = fakeTrack({ name: NOISE_FILTER_NAME });
    const { d } = deps();
    syncNoiseFilter(fakeRoom(mic).room, true, d);
    await settle();
    expect(mic.setProcessor).not.toHaveBeenCalled();
  });

  it('turned off: removes our filter, leaves any other processor alone', async () => {
    const mic = fakeTrack({ name: NOISE_FILTER_NAME });
    syncNoiseFilter(fakeRoom(mic).room, false, deps().d);
    await settle();
    expect(mic.stopProcessor).toHaveBeenCalledTimes(1);
    expect(mic.processor).toBeUndefined();

    const other = fakeTrack({ name: 'someone-else' });
    syncNoiseFilter(fakeRoom(other).room, false, deps().d);
    await settle();
    expect(other.stopProcessor).not.toHaveBeenCalled();
  });

  it('unsupported browser: the plain microphone stays as it is', async () => {
    const mic = fakeTrack();
    const { d } = deps({ supported: false });
    syncNoiseFilter(fakeRoom(mic).room, true, d);
    await settle();
    expect(mic.setProcessor).not.toHaveBeenCalled();
    expect(d.onUnavailable).not.toHaveBeenCalled();
  });

  it('a filter that fails to start is reported and the microphone keeps working without it', async () => {
    const mic = fakeTrack();
    mic.setProcessor.mockRejectedValueOnce(new Error('wasm blocked'));
    const { d } = deps();
    syncNoiseFilter(fakeRoom(mic).room, true, d);
    await settle();
    expect(d.onUnavailable).toHaveBeenCalledWith(expect.objectContaining({ message: 'wasm blocked' }));
    expect(mic.processor).toBeUndefined();
  });

  it('audio blocked until the user interacts (page reload): retries on the next click', async () => {
    const mic = fakeTrack();
    mic.setProcessor.mockRejectedValueOnce(new AudioBlockedError());
    const { d, gesture } = deps();
    syncNoiseFilter(fakeRoom(mic).room, true, d);
    await settle();
    expect(d.onUnavailable).not.toHaveBeenCalled();
    expect(mic.processor).toBeUndefined();
    gesture()!();
    await settle();
    expect(mic.setProcessor).toHaveBeenCalledTimes(2);
    expect(mic.processor?.name).toBe(NOISE_FILTER_NAME);
  });

  it('turning it off while it is still starting ends with it off', async () => {
    const mic = fakeTrack();
    let finish!: () => void;
    mic.setProcessor.mockImplementationOnce(
      (p: Processor) =>
        new Promise<void>((r) => {
          finish = () => {
            mic.processor = p;
            r();
          };
        }),
    );
    const r = fakeRoom(mic);
    const stopOn = syncNoiseFilter(r.room, true, deps().d);
    await settle();
    stopOn();
    syncNoiseFilter(r.room, false, deps().d);
    finish();
    await settle();
    await settle();
    expect(mic.stopProcessor).toHaveBeenCalledTimes(1);
    expect(mic.processor).toBeUndefined();
  });

  it('cleanup stops listening and cancels a pending retry', async () => {
    const mic = fakeTrack();
    mic.setProcessor.mockRejectedValueOnce(new AudioBlockedError());
    const r = fakeRoom(mic);
    const { d, gesture } = deps();
    const cleanup = syncNoiseFilter(r.room, true, d);
    await settle();
    expect(r.listeners()).toBe(1);
    expect(gesture()).not.toBeNull();
    cleanup();
    expect(r.listeners()).toBe(0);
    expect(gesture()).toBeNull();
  });
});
