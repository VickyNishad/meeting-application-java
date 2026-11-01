import { describe, expect, it, vi } from 'vitest';
import { act, render } from '@testing-library/react';
import type { ReactNode } from 'react';
import type { Room } from 'livekit-client';

const counts = vi.hoisted(() => ({ grid: 0, tiles: new Map<string, number>() }));

vi.mock('@livekit/components-react', async (orig) => {
  const real = await orig<typeof import('@livekit/components-react')>();
  return {
    ...real,
    // The real grid renders each track inside a TrackRefContext with a clone of the child; so does this stand-in.
    GridLayout: ({ tracks, children }: { tracks: { participant: { identity: string } }[]; children: ReactNode }) => {
      counts.grid++;
      return (
        <>
          {tracks.map((t) => (
            <real.TrackRefContext.Provider key={t.participant.identity} value={t as never}>
              {children}
            </real.TrackRefContext.Provider>
          ))}
        </>
      );
    },
    ParticipantTile: () => {
      const ref = real.useMaybeTrackRefContext();
      const id = ref?.participant.identity ?? '?';
      counts.tiles.set(id, (counts.tiles.get(id) ?? 0) + 1);
      return null;
    },
  };
});

import { GridStage } from './RoomView';
import { MeetingRoomContext, type MeetingRoomState } from './roomContext';

class FakeEmitter {
  private listeners = new Map<string, Set<(...a: unknown[]) => void>>();
  on(e: string, f: (...a: unknown[]) => void) {
    if (!this.listeners.has(e)) this.listeners.set(e, new Set());
    this.listeners.get(e)!.add(f);
    return this;
  }
  off(e: string, f: (...a: unknown[]) => void) {
    this.listeners.get(e)?.delete(f);
    return this;
  }
  emit(e: string, ...a: unknown[]) {
    for (const f of [...(this.listeners.get(e) ?? [])]) f(...a);
  }
}

const person = (identity: string) =>
  Object.assign(new FakeEmitter(), { identity, name: identity, isSpeaking: false, isMicrophoneEnabled: true, connectionQuality: 'good' });

describe('GridStage (QA SP-2, SP-6)', () => {
  it('re-renders the grid when the speakers change (pages re-sorted), but not the tiles', () => {
    const people = ['a', 'b', 'c'].map(person);
    const tracks = people.map((participant) => ({ participant, source: 'camera' }));
    const room = new FakeEmitter() as unknown as Room;
    const ctx = { names: {} } as unknown as MeetingRoomState;
    render(
      <MeetingRoomContext.Provider value={ctx}>
        <GridStage tracks={tracks as never} speakerRooms={[room]} />
      </MeetingRoomContext.Provider>,
    );
    const gridBefore = counts.grid;
    const tilesBefore = new Map(counts.tiles);
    expect([...tilesBefore.keys()].sort()).toEqual(['a', 'b', 'c']);

    act(() => (room as unknown as FakeEmitter).emit('activeSpeakersChanged', [people[1]]));
    expect(counts.grid).toBe(gridBefore + 1);
    expect(counts.tiles).toEqual(tilesBefore);
  });
});
