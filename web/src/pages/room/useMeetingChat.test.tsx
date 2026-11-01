import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RoomEvent } from 'livekit-client';
import { chatApi } from '@/lib/api/endpoints';
import type { ChatMessage } from '@/lib/api/types';
import { encodePacket } from './roomData';
import { useMeetingChat } from './useMeetingChat';

const lk = vi.hoisted(() => ({
  listeners: new Map<string, Set<() => void>>(),
  onData: null as null | ((msg: { from?: { identity: string }; payload: Uint8Array }) => void),
}));

vi.mock('@livekit/components-react', () => ({
  useRoomContext: () => ({
    on: (e: string, fn: () => void) => {
      if (!lk.listeners.has(e)) lk.listeners.set(e, new Set());
      lk.listeners.get(e)!.add(fn);
    },
    off: (e: string, fn: () => void) => lk.listeners.get(e)?.delete(fn),
  }),
  useDataChannel: (_topic: string, cb: typeof lk.onData) => {
    lk.onData = cb;
    return { send: vi.fn() };
  },
}));

const msg = (id: string, sec: number, text = id): ChatMessage => ({
  id,
  meetingId: 'm1',
  participantId: 'p2',
  senderName: 'Bo',
  text,
  sentAt: `2026-10-28T10:00:${String(sec).padStart(2, '0')}Z`,
});

describe('useMeetingChat after reconnect (W-08)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    lk.listeners.clear();
  });

  it('refetches history on remount and on RoomEvent.Reconnected, merging with live messages without duplicates', async () => {
    const history = vi.spyOn(chatApi, 'history').mockResolvedValueOnce([msg('a', 1)]);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>;

    const first = renderHook(() => useMeetingChat('m1', 'p1', 'w1'), { wrapper });
    await waitFor(() => expect(first.result.current.messages.map((m) => m.id)).toEqual(['a']));
    first.unmount(); // Reconnect / Rejoin remounts the room view (cache still warm)

    // Meanwhile "b" was sent; after the remount a live packet "c" arrives too.
    history.mockResolvedValueOnce([msg('a', 1), msg('b', 2)]);
    const second = renderHook(() => useMeetingChat('m1', 'p1', 'w1'), { wrapper });
    await waitFor(() => expect(second.result.current.messages.map((m) => m.id)).toEqual(['a', 'b']));
    act(() => lk.onData?.({ payload: encodePacket({ type: 'chat', message: msg('c', 3) }) }));
    expect(second.result.current.messages.map((m) => m.id)).toEqual(['a', 'b', 'c']);

    // A LiveKit-level reconnect (no remount) missed "d": history is fetched again.
    history.mockResolvedValueOnce([msg('b', 2), msg('c', 3), msg('d', 4)]);
    act(() => lk.listeners.get(RoomEvent.Reconnected)?.forEach((fn) => fn()));
    await waitFor(() => expect(second.result.current.messages.map((m) => m.id)).toEqual(['a', 'b', 'c', 'd']));
    expect(history).toHaveBeenCalledTimes(3);
  });
});
