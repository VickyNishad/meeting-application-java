import { describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import type { Room } from 'livekit-client';

const recorder = vi.hoisted(() => ({ audioTracks: null as null | (() => MediaStreamTrack[]) }));

vi.mock('@/lib/recorder/localRecorder', () => ({
  detectLocalRecordingSupport: () => ({ supported: true }),
  downloadBlob: vi.fn(),
  recordingFileName: () => 'rec.webm',
  RecorderError: class extends Error {},
  LocalRecorder: class {
    extension = 'webm';
    active = false;
    constructor(opts: { audioTracks: () => MediaStreamTrack[] }) {
      recorder.audioTracks = opts.audioTracks;
    }
    async acquire() {}
    start() {}
    abort() {}
  },
}));
vi.mock('@/lib/api/endpoints', () => ({ recordingsApi: { localState: vi.fn(async () => undefined) } }));

const fakeRoom = (tracks: Record<string, string[]>, localMic?: string) =>
  ({
    localParticipant: {
      getTrackPublication: () => (localMic ? { track: { mediaStreamTrack: { id: localMic } } } : undefined),
    },
    remoteParticipants: new Map(
      Object.entries(tracks).map(([identity, ids]) => [
        identity,
        { audioTrackPublications: new Map(ids.map((id) => [id, { track: { mediaStreamTrack: { id } } }])) },
      ]),
    ),
    on: vi.fn(),
    off: vi.fn(),
  }) as unknown as Room;

const stage = fakeRoom({ host2: ['stage-cohost-audio'] }, 'my-mic');
const attendeeRooms = [fakeRoom({ a: ['attendee-a-audio'] }), fakeRoom({ b: ['attendee-b-audio'] })];

vi.mock('@livekit/components-react', () => ({ useRoomContext: () => stage }));

import { AudienceRoomsContext, StageRoomContext } from './useAudienceRoom';
import { useLocalRecording } from './useLocalRecording';

describe('useLocalRecording (QA56-15)', () => {
  it("a HOST_AND_SELF_ONLY moderator's local recording mixes in the attendees heard in their audience rooms", async () => {
    const wrapper = ({ children }: { children: ReactNode }) => (
      <StageRoomContext.Provider value={stage}>
        <AudienceRoomsContext.Provider value={attendeeRooms}>{children}</AudienceRoomsContext.Provider>
      </StageRoomContext.Provider>
    );
    const { result } = renderHook(() => useLocalRecording('m1', 'me', 'secret', 'Webinar'), { wrapper });
    await act(async () => {
      await result.current.start();
    });
    const ids = recorder.audioTracks!().map((t) => t.id);
    expect(ids).toEqual(expect.arrayContaining(['my-mic', 'stage-cohost-audio', 'attendee-a-audio', 'attendee-b-audio']));
  });
});
