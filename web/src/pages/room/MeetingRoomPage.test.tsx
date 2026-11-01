import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useParams } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DisconnectReason, MediaDeviceFailure } from 'livekit-client';
import { toast } from 'sonner';
import { StaticAuthProvider, type AuthContextValue, type AuthStatus } from '@/lib/auth/AuthProvider';
import { meetingsApi } from '@/lib/api/endpoints';
import type { JoinResponse, Meeting } from '@/lib/api/types';
import { clearJoin, getMediaState, saveJoin, saveMediaState } from '@/lib/joinStore';
import type { MediaState } from '@/lib/joinStore';
import type { EndReason } from './roomContext';
import { ThemeProvider } from '@/lib/theme';
import MeetingRoomPage from './MeetingRoomPage';

interface RoomProps {
  token: string;
  connect: boolean;
  audio: boolean;
  video: boolean;
  onError: (e: Error) => void;
  onConnected: () => void;
  onDisconnected: (r?: DisconnectReason) => void;
  onMediaDeviceFailure: (f?: MediaDeviceFailure, kind?: MediaDeviceKind) => void;
  children: React.ReactNode;
}

const lk = vi.hoisted(() => ({ props: [] as unknown[] }));

vi.mock('@livekit/components-react', () => ({
  LiveKitRoom: (p: { children: React.ReactNode }) => {
    lk.props.push(p);
    return <div data-testid="livekit-room">{p.children}</div>;
  },
}));

vi.mock('./RoomView', () => ({
  RoomView: (p: { connectError: Error | null; onRetry: () => void; finish: (r: EndReason) => void; onMediaChange: (m: MediaState) => void }) => (
    <div>
      {p.connectError && (
        <p role="alert">
          {p.connectError.message}
          <button onClick={p.onRetry}>Reconnect</button>
        </p>
      )}
      <button onClick={() => p.finish('left')}>Leave</button>
      <button onClick={() => p.finish('removed')}>Removed</button>
      <button onClick={() => p.onMediaChange({ audioEnabled: false, videoEnabled: true })}>Mute mic</button>
    </div>
  ),
}));

const lastProps = () => lk.props[lk.props.length - 1] as RoomProps;

const meeting = {
  id: 'm1',
  joinCode: 'abc-defg-hij',
  title: 'Weekly sync',
  mediaMode: 'VIDEO',
  muteOnEntry: false,
  status: 'LIVE',
  host: { id: 'u1', displayName: 'Ada' },
} as Meeting;

const admitted = (token = 'lk-1'): JoinResponse => ({
  status: 'ADMITTED',
  participantId: 'p1',
  waitToken: 'w1',
  role: 'GUEST',
  token,
  livekitUrl: 'wss://lk.example',
  meeting,
});

function ctx(status: AuthStatus): AuthContextValue {
  return { status, user: null, signIn: () => undefined, signOut: async () => undefined, setUser: () => undefined };
}

function JoinPageProbe() {
  const { joinCode } = useParams();
  return <p>pre-join for {joinCode}</p>;
}

function renderRoom(status: AuthStatus = 'anonymous') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider>
    <StaticAuthProvider value={ctx(status)}>
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/meetings/m1/room']}>
          <Routes>
            <Route path="/meetings/:meetingId/room" element={<MeetingRoomPage />} />
            <Route path="/j/:joinCode" element={<JoinPageProbe />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </StaticAuthProvider>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  lk.props.length = 0;
  clearJoin('m1');
  window.sessionStorage.clear();
});
afterEach(() => vi.restoreAllMocks());

describe('MeetingRoomPage connection handling (W-01)', () => {
  it('passes stable callbacks and stops connecting after a fatal disconnect until the user reconnects', async () => {
    saveJoin('m1', admitted());
    saveMediaState('m1', { audioEnabled: true, videoEnabled: true });
    const joinStatus = vi.spyOn(meetingsApi, 'joinStatus').mockResolvedValue(admitted('lk-2'));
    renderRoom();
    const first = lastProps();
    expect(first.connect).toBe(true);
    act(() => first.onConnected());
    act(() => first.onDisconnected(DisconnectReason.SIGNAL_CLOSE));

    expect(screen.getByRole('alert')).toHaveTextContent(/disconnected from the meeting/i);
    const after = lastProps();
    // Same callback identities → LiveKitRoom's connect effect does not re-run on these re-renders …
    expect(after.onError).toBe(first.onError);
    expect(after.onDisconnected).toBe(first.onDisconnected);
    expect(after.onMediaDeviceFailure).toBe(first.onMediaDeviceFailure);
    // … and it is told to stay disconnected.
    expect(after.connect).toBe(false);
    expect(joinStatus).not.toHaveBeenCalled();

    // Only the user's Reconnect fetches a fresh token and connects again.
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect' }));
    expect(await screen.findByTestId('livekit-room')).toBeInTheDocument();
    expect(joinStatus).toHaveBeenCalledTimes(1);
    expect(lastProps().token).toBe('lk-2');
    expect(lastProps().connect).toBe(true);
  });

  it('a duplicate-identity disconnect (same participant in another tab) does not ping-pong', () => {
    saveJoin('m1', admitted());
    renderRoom();
    const before = lk.props.length;
    act(() => lastProps().onDisconnected(DisconnectReason.DUPLICATE_IDENTITY));
    expect(screen.getByRole('alert')).toHaveTextContent(/another tab or device/i);
    expect(lastProps().connect).toBe(false);
    expect(lk.props.length - before).toBeLessThanOrEqual(1);
  });

  it('a connect error before the room is connected shows the Reconnect banner', () => {
    saveJoin('m1', admitted());
    renderRoom();
    act(() => lastProps().onError(new Error('could not establish signal connection')));
    expect(screen.getByRole('alert')).toHaveTextContent(/could not establish signal connection/);
    expect(lastProps().connect).toBe(false);
  });
});

describe('MeetingRoomPage media devices (W-07, W-02)', () => {
  it('a denied microphone is a non-fatal notice, turns the mic off and is remembered', () => {
    const warn = vi.spyOn(toast, 'warning').mockImplementation(() => 'id');
    saveJoin('m1', admitted());
    saveMediaState('m1', { audioEnabled: true, videoEnabled: true });
    renderRoom();
    expect(lastProps().audio).toBe(true);
    act(() => {
      lastProps().onMediaDeviceFailure(MediaDeviceFailure.PermissionDenied, 'audioinput');
      // The library also rejects its publish Promise.all with the same error.
      lastProps().onError(new DOMException('Permission denied', 'NotAllowedError'));
    });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(lastProps().connect).toBe(true);
    expect(warn).toHaveBeenCalledWith(expect.stringMatching(/microphone was denied/i), expect.anything());
    expect(getMediaState('m1')).toEqual({ audioEnabled: false, videoEnabled: true });
    expect(lastProps().audio).toBe(false);
    expect(lastProps().video).toBe(true);
  });

  it('screen-share failures (no device kind) do not touch the mic/camera state', () => {
    saveJoin('m1', admitted());
    saveMediaState('m1', { audioEnabled: true, videoEnabled: true });
    renderRoom();
    act(() => lastProps().onMediaDeviceFailure(MediaDeviceFailure.PermissionDenied, undefined));
    expect(getMediaState('m1')).toEqual({ audioEnabled: true, videoEnabled: true });
  });

  it('reconnects with the latest in-meeting state, not with mic+camera on', async () => {
    saveJoin('m1', admitted());
    saveMediaState('m1', { audioEnabled: true, videoEnabled: true });
    vi.spyOn(meetingsApi, 'joinStatus').mockResolvedValue(admitted('lk-2'));
    renderRoom();
    await userEvent.click(screen.getByRole('button', { name: 'Mute mic' }));
    act(() => lastProps().onDisconnected(DisconnectReason.SIGNAL_CLOSE));
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect' }));
    await screen.findByTestId('livekit-room');
    expect(lastProps().token).toBe('lk-2');
    expect(lastProps().audio).toBe(false);
    expect(lastProps().video).toBe(true);
  });

  it('after a reload with no recorded state it joins with mic and camera OFF', async () => {
    // Reload: nothing in memory, only the participant secret in sessionStorage.
    window.sessionStorage.setItem('mp-join:m1', JSON.stringify({ participantId: 'p1', waitToken: 'w1', joinCode: 'abc-defg-hij' }));
    vi.spyOn(meetingsApi, 'joinStatus').mockResolvedValue(admitted('lk-3'));
    renderRoom();
    await screen.findByTestId('livekit-room');
    expect(lastProps().audio).toBe(false);
    expect(lastProps().video).toBe(false);
  });
});

describe('MeetingRoomPage leave / rejoin (W-06)', () => {
  it('a guest who left rejoins through the meeting pre-join (/j/{joinCode}), not a sign-in card', async () => {
    saveJoin('m1', admitted());
    const joinStatus = vi.spyOn(meetingsApi, 'joinStatus');
    renderRoom('anonymous');
    await userEvent.click(screen.getByRole('button', { name: 'Leave' }));
    expect(screen.getByRole('heading', { name: /you left the meeting/i })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Rejoin' }));
    expect(await screen.findByText('pre-join for abc-defg-hij')).toBeInTheDocument();
    expect(joinStatus).not.toHaveBeenCalled();
  });

  it('a guest whose participant was marked LEFT is sent to the pre-join when Reconnect gets DENIED', async () => {
    saveJoin('m1', admitted());
    vi.spyOn(meetingsApi, 'joinStatus').mockResolvedValue({ ...admitted(), status: 'DENIED', token: undefined });
    renderRoom('anonymous');
    act(() => lastProps().onDisconnected(DisconnectReason.SIGNAL_CLOSE));
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect' }));
    expect(await screen.findByText('pre-join for abc-defg-hij')).toBeInTheDocument();
    expect(screen.queryByText(/sign in/i)).not.toBeInTheDocument();
  });

  it('a member who left rejoins with a fresh join instead of resuming the LEFT participant', async () => {
    saveJoin('m1', admitted());
    const joinStatus = vi.spyOn(meetingsApi, 'joinStatus');
    vi.spyOn(meetingsApi, 'get').mockResolvedValue({ ...meeting, passwordProtected: false, waitingRoomEnabled: false, myRole: 'PARTICIPANT' });
    renderRoom('authenticated');
    await userEvent.click(screen.getByRole('button', { name: 'Leave' }));
    await userEvent.click(screen.getByRole('button', { name: 'Rejoin' }));
    expect(await screen.findByRole('button', { name: 'Join meeting' })).toBeInTheDocument();
    expect(joinStatus).not.toHaveBeenCalled();
  });

  it('offers no Rejoin after being removed', async () => {
    saveJoin('m1', admitted());
    renderRoom('anonymous');
    await userEvent.click(screen.getByRole('button', { name: 'Removed' }));
    expect(screen.getByRole('heading', { name: /removed from the meeting/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Rejoin' })).not.toBeInTheDocument();
  });
});
