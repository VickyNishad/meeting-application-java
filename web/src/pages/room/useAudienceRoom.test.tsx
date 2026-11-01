import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { AudienceRoomGrant } from '@/lib/api/types';

const lk = vi.hoisted(() => {
  const rooms: FakeRoom[] = [];
  class FakeRoom {
    calls: string[] = [];
    listeners = new Map<string, ((...a: unknown[]) => void)[]>();
    remoteParticipants = new Map<string, unknown>();
    localParticipant = {
      setMicrophoneEnabled: vi.fn(async () => {
        this.calls.push('mic');
      }),
      setCameraEnabled: vi.fn(async () => {
        this.calls.push('camera');
      }),
    };
    constructor(public options: unknown) {
      rooms.push(this);
    }
    async connect(url: string, token: string) {
      this.calls.push(`connect:${url}:${token}`);
    }
    async disconnect() {
      this.calls.push('disconnect');
    }
    on(e: string, f: (...a: unknown[]) => void) {
      this.listeners.set(e, [...(this.listeners.get(e) ?? []), f]);
    }
    off(e: string, f: (...a: unknown[]) => void) {
      this.listeners.set(e, (this.listeners.get(e) ?? []).filter((x) => x !== f));
    }
    emit(e: string, ...a: unknown[]) {
      for (const f of this.listeners.get(e) ?? []) f(...a);
    }
  }
  return { rooms, FakeRoom };
});
type FakeRoom = InstanceType<typeof lk.FakeRoom>;
const FakeRoom = lk.FakeRoom;

vi.mock('livekit-client', async (orig) => ({ ...(await orig<typeof import('livekit-client')>()), Room: lk.FakeRoom }));

import { useAudienceRooms, useOwnAudienceParticipant } from './useAudienceRoom';

const grant = (participantId: string, token = `tok-${participantId}`): AudienceRoomGrant => ({
  participantId,
  roomName: `stage-aud-${participantId}`,
  token,
});
const none = () => undefined;

afterEach(() => {
  lk.rooms.length = 0;
});

describe('useAudienceRooms (HOST_AND_SELF_ONLY, one audience room per attendee)', () => {
  it('connects an attendee to its own room and publishes its devices there', async () => {
    renderHook(() => useAudienceRooms('wss://lk', [grant('me')], {}, { audioEnabled: true, videoEnabled: true }, none, none));
    await vi.waitFor(() => expect(lk.rooms[0]?.calls).toContain('camera'));
    expect(lk.rooms[0].calls).toEqual(['connect:wss://lk:tok-me', 'mic', 'camera']);
  });

  it('does nothing without grants (EVERYONE_VISIBLE)', () => {
    const { result } = renderHook(() => useAudienceRooms('wss://lk', undefined, {}, null, none, none));
    expect(result.current).toEqual([]);
    expect(lk.rooms).toHaveLength(0);
  });

  it('connects the stage to every attendee room, publishing nothing, and follows attendees coming and going', async () => {
    const { result, rerender } = renderHook(({ grants }) => useAudienceRooms('wss://lk', grants, {}, null, none, none), {
      initialProps: { grants: [grant('a'), grant('b')] },
    });
    expect(result.current).toHaveLength(2);
    await vi.waitFor(() => expect(lk.rooms.map((r) => r.calls[0])).toEqual(['connect:wss://lk:tok-a', 'connect:wss://lk:tok-b']));
    expect(lk.rooms.flatMap((r) => r.calls)).not.toContain('mic');

    // b left, c joined; refreshed tokens for a do not reconnect a.
    rerender({ grants: [grant('a', 'tok-a-2'), grant('c')] });
    expect(result.current).toHaveLength(2);
    expect(lk.rooms[1].calls).toContain('disconnect');
    expect(lk.rooms).toHaveLength(3);
    expect(lk.rooms[0].calls).not.toContain('disconnect');
  });

  it('does not reconnect on re-renders and disconnects everything on unmount', async () => {
    const { rerender, unmount } = renderHook(({ opts }) => useAudienceRooms('wss://lk', [grant('a')], opts, null, none, none), {
      initialProps: { opts: {} },
    });
    rerender({ opts: {} });
    rerender({ opts: {} });
    expect(lk.rooms).toHaveLength(1);
    unmount();
    await vi.waitFor(() => expect(lk.rooms[0].calls).toContain('disconnect'));
  });

  it('QA56-11: a dropped connection is reported and connected again with the fresh grant', async () => {
    const onLost = vi.fn();
    const { result, rerender } = renderHook(({ grants }) => useAudienceRooms('wss://lk', grants, {}, null, none, onLost), {
      initialProps: { grants: [grant('me', 't1')] },
    });
    await vi.waitFor(() => expect(lk.rooms[0].calls).toContain('connect:wss://lk:t1'));
    act(() => lk.rooms[0].emit('disconnected'));
    expect(onLost).toHaveBeenCalledTimes(1);
    expect(result.current).toHaveLength(0);

    rerender({ grants: [grant('me', 't2')] });
    expect(lk.rooms).toHaveLength(2);
    await vi.waitFor(() => expect(lk.rooms[1].calls).toContain('connect:wss://lk:t2'));
    expect(result.current).toHaveLength(1);
  });

  it('our own disconnects are not reported as lost', async () => {
    const onLost = vi.fn();
    const { rerender } = renderHook(({ grants }) => useAudienceRooms('wss://lk', grants, {}, null, none, onLost), {
      initialProps: { grants: [grant('a')] },
    });
    rerender({ grants: [] });
    act(() => lk.rooms[0].emit('disconnected'));
    expect(onLost).not.toHaveBeenCalled();
  });

  it('reports a failed connection (and asks for fresh grants) but not missing devices', async () => {
    const onError = vi.fn();
    const onLost = vi.fn();
    renderHook(() => useAudienceRooms('wss://lk', [grant('me')], {}, { audioEnabled: true, videoEnabled: true }, onError, onLost));
    const room = lk.rooms[0];
    room.localParticipant.setMicrophoneEnabled.mockRejectedValueOnce(Object.assign(new Error('no mic'), { name: 'NotFoundError' }));
    await vi.waitFor(() => expect(room.calls).toContain('camera'));
    expect(onError).not.toHaveBeenCalled();

    lk.rooms.length = 0;
    const proto = FakeRoom.prototype as unknown as { connect: () => Promise<void> };
    const spy = vi.spyOn(proto, 'connect').mockRejectedValueOnce(new Error('could not establish signal connection'));
    renderHook(() => useAudienceRooms('wss://lk', [grant('other')], {}, null, onError, onLost));
    await vi.waitFor(() => expect(onError).toHaveBeenCalled());
    expect(onLost).toHaveBeenCalled();
    spy.mockRestore();
  });
});

describe('useOwnAudienceParticipant (QA SP-9)', () => {
  it("is the attendee's participant in its audience room, only while that room is connected", () => {
    const listeners = new Map<string, Set<() => void>>();
    const local = { identity: 'me', isLocal: true };
    const room = {
      state: 'connecting',
      localParticipant: local,
      on: (e: string, f: () => void) => listeners.set(e, new Set([...(listeners.get(e) ?? []), f])),
      off: (e: string, f: () => void) => listeners.get(e)?.delete(f),
    };
    const { result, unmount } = renderHook(() => useOwnAudienceParticipant([room as never]));
    expect(result.current).toBeNull();
    act(() => {
      room.state = 'connected';
      listeners.get('connectionStateChanged')?.forEach((f) => f());
    });
    expect(result.current).toBe(local);
    unmount();
    expect([...listeners.values()].every((s) => s.size === 0)).toBe(true);
  });

  it('is nothing without rooms (the stage, or a normal meeting)', () => {
    const { result } = renderHook(() => useOwnAudienceParticipant([]));
    expect(result.current).toBeNull();
  });
});
