import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { LiveKitRoom } from '@livekit/components-react';
import { DisconnectReason, type MediaDeviceFailure, type RoomOptions } from 'livekit-client';
import '@livekit/components-styles';
import { CalendarX2, DoorOpen, LogIn, ShieldX } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/primitives';
import { ErrorState } from '@/components/common';
import { PublicShell } from '@/components/layout/AppShell';
import { FullPageSpinner } from '@/lib/auth/guards';
import { useAuth } from '@/lib/auth/AuthProvider';
import { meetingsApi } from '@/lib/api/endpoints';
import type { JoinResponse } from '@/lib/api/types';
import { errorMessage } from '@/lib/api/problem';
import {
  clearJoin,
  getDevicePrefs,
  getFreshAdmittedJoin,
  getMediaState,
  getStoredParticipant,
  saveJoin,
  saveMediaState,
  type MediaState,
} from '@/lib/joinStore';
import { JoinFlow, WaitingRoom } from './JoinFlow';
import { RoomView } from './RoomView';
import type { EndReason } from './roomContext';
import { deviceFailureMessage, isMediaDeviceError, type MediaKind } from './mediaErrors';

function EndScreen({ reason, meetingId, onRejoin }: { reason: EndReason; meetingId: string; onRejoin?: () => void }) {
  const { status } = useAuth();
  const content = {
    left: { icon: DoorOpen, title: 'You left the meeting', text: 'The meeting continues for everyone else.' },
    ended: { icon: CalendarX2, title: 'The meeting has ended', text: 'The host ended this meeting for everyone.' },
    removed: { icon: ShieldX, title: 'You were removed from the meeting', text: 'A host removed you from this meeting.' },
  }[reason];
  return (
    <PublicShell>
      <Card className="mx-auto mt-16 w-full max-w-md">
        <CardContent className="flex flex-col items-center gap-3 p-8 text-center">
          <content.icon className="size-10 text-primary" aria-hidden="true" />
          <h1 className="text-lg font-semibold">{content.title}</h1>
          <p className="text-sm text-muted-foreground">{content.text}</p>
          <div className="mt-2 flex flex-wrap justify-center gap-2">
            {reason === 'left' && onRejoin && <Button onClick={onRejoin}>Rejoin</Button>}
            {status === 'authenticated' ? (
              <>
                <Button variant="outline" asChild>
                  <Link to={`/meetings/${meetingId}`}>Meeting details</Link>
                </Button>
                <Button variant="outline" asChild>
                  <Link to="/dashboard">Dashboard</Link>
                </Button>
              </>
            ) : (
              <Button variant="outline" asChild>
                <Link to="/register">Create a free account</Link>
              </Button>
            )}
          </div>
        </CardContent>
      </Card>
    </PublicShell>
  );
}

/**
 * Re-obtains a LiveKit token after a refresh using the participant secret in sessionStorage.
 * onFail receives the meeting's join code (when known) so a guest can be sent back through the normal pre-join.
 */
function ResumeJoin({ meetingId, participantId, waitToken, joinCode, onResolved, onFail }: {
  meetingId: string;
  participantId: string;
  waitToken: string;
  joinCode?: string;
  onResolved: (j: JoinResponse) => void;
  onFail: (joinCode?: string) => void;
}) {
  const q = useQuery({
    queryKey: ['join-resume', meetingId, participantId],
    queryFn: () => meetingsApi.joinStatus(meetingId, participantId, waitToken),
    retry: 1,
    gcTime: 0,
  });
  useEffect(() => {
    if (q.data?.status === 'ADMITTED') {
      // join-status may omit waitToken; keep the participant secret we already hold.
      const admitted = { ...q.data, waitToken: q.data.waitToken || waitToken };
      saveJoin(meetingId, admitted);
      onResolved(admitted);
    } else if (q.data?.status === 'DENIED' || q.isError) {
      // DENIED also means the participant has LEFT (left, or dropped and marked LEFT): it needs a fresh join.
      clearJoin(meetingId);
      onFail(q.data?.meeting?.joinCode ?? joinCode);
    }
  }, [q.data, q.isError, meetingId, waitToken, joinCode, onResolved, onFail]);

  if (q.data?.status === 'WAITING') {
    return (
      <PublicShell>
        <div className="py-12">
          <WaitingRoom
            meetingId={meetingId}
            participantId={participantId}
            waitToken={waitToken}
            title={q.data.meeting.title}
            initialMeeting={q.data.meeting}
            onAdmitted={onResolved}
            onCancel={() => onFail(q.data?.meeting?.joinCode ?? joinCode)}
          />
        </div>
      </PublicShell>
    );
  }
  return <FullPageSpinner label="Reconnecting to the meeting…" />;
}

function PreJoinForMember({ meetingId, onAdmitted }: { meetingId: string; onAdmitted: (j: JoinResponse) => void }) {
  const { status, user } = useAuth();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['meeting', meetingId], queryFn: () => meetingsApi.get(meetingId), enabled: status === 'authenticated' });

  if (status === 'loading') return <FullPageSpinner />;
  if (status !== 'authenticated') {
    return (
      <PublicShell>
        <Card className="mx-auto mt-16 w-full max-w-md">
          <CardContent className="flex flex-col items-center gap-3 p-8 text-center">
            <LogIn className="size-10 text-primary" aria-hidden="true" />
            <h1 className="text-lg font-semibold">Join this meeting</h1>
            <p className="text-sm text-muted-foreground">Sign in, or open the meeting's join link to join as a guest.</p>
            <Button asChild>
              <Link to={`/login?next=${encodeURIComponent(`/meetings/${meetingId}/room`)}`}>Sign in</Link>
            </Button>
          </CardContent>
        </Card>
      </PublicShell>
    );
  }
  if (q.isPending) return <FullPageSpinner />;
  if (q.isError) {
    return (
      <PublicShell>
        <div className="mx-auto w-full max-w-md py-12">
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        </div>
      </PublicShell>
    );
  }
  const m = q.data;
  if (m.status === 'ENDED' || m.status === 'CANCELLED') {
    return <EndScreen reason="ended" meetingId={meetingId} />;
  }
  return (
    <PublicShell>
      <div className="py-6">
        <JoinFlow
          target={{
            meetingId,
            title: m.title,
            hostName: m.host.displayName,
            mediaMode: m.mediaMode,
            passwordRequired: m.passwordProtected && m.myRole !== 'HOST',
            guest: false,
            defaultDisplayName: user?.displayName,
            waitingRoomEnabled: m.waitingRoomEnabled,
            autoRecord: m.autoRecord,
            recordingInProgress: m.recordingInProgress,
          }}
          onAdmitted={onAdmitted}
          onCancel={() => navigate(-1)}
        />
      </div>
    </PublicShell>
  );
}

export default function MeetingRoomPage() {
  const { meetingId = '' } = useParams();
  const navigate = useNavigate();
  const { status: authStatus } = useAuth();
  const [join, setJoin] = useState<JoinResponse | null>(() => getFreshAdmittedJoin(meetingId) ?? null);
  const [stored, setStored] = useState(() => (join ? null : getStoredParticipant(meetingId)));
  // Kept after leaving so a guest can go back through the meeting's /j/{joinCode} pre-join.
  const [joinCode, setJoinCode] = useState<string | undefined>(() => join?.meeting.joinCode ?? stored?.joinCode);
  const [ended, setEnded] = useState<EndReason | null>(null);
  const [connectError, setConnectError] = useState<Error | null>(null);
  // The mic/camera state to publish on (re)connect: the user's latest choice, both off when unknown.
  const [media, setMedia] = useState<MediaState>(() => getMediaState(meetingId));
  const connectedRef = useRef(false);

  const onAdmitted = useCallback(
    (j: JoinResponse) => {
      if (j.meeting?.joinCode) setJoinCode(j.meeting.joinCode);
      setMedia(getMediaState(meetingId));
      setJoin(j);
      setEnded(null);
      setConnectError(null);
    },
    [meetingId],
  );

  /** Guests have no account to fall back on: send them through the meeting's public pre-join. */
  const toGuestJoinPage = useCallback(
    (code: string | undefined) => {
      if (authStatus === 'authenticated' || !code) return false;
      navigate(`/j/${encodeURIComponent(code)}`, { replace: true });
      return true;
    },
    [authStatus, navigate],
  );

  const onResumeFail = useCallback(
    (code?: string) => {
      setStored(null);
      toGuestJoinPage(code ?? joinCode);
    },
    [toGuestJoinPage, joinCode],
  );

  const finish = useCallback(
    (reason: EndReason) => {
      connectedRef.current = false;
      setEnded(reason);
      setJoin(null);
      if (reason !== 'left') clearJoin(meetingId);
    },
    [meetingId],
  );

  // A participant who left is LEFT on the server and cannot be resumed: rejoining is a fresh join.
  const rejoin = useCallback(() => {
    if (toGuestJoinPage(joinCode)) return;
    clearJoin(meetingId);
    setStored(null);
    setEnded(null);
  }, [meetingId, joinCode, toGuestJoinPage]);

  const onMediaChange = useCallback(
    (m: MediaState) => {
      saveMediaState(meetingId, m);
      setMedia((cur) => (cur.audioEnabled === m.audioEnabled && cur.videoEnabled === m.videoEnabled ? cur : m));
    },
    [meetingId],
  );

  // Stable callbacks: LiveKitRoom re-runs room.connect() whenever onError changes identity.
  const onConnected = useCallback(() => {
    connectedRef.current = true;
  }, []);

  const onError = useCallback((e: Error) => {
    // Device problems are reported (non-fatally) through onMediaDeviceFailure.
    if (isMediaDeviceError(e)) return;
    if (connectedRef.current) {
      toast.error(`Could not publish your microphone or camera: ${errorMessage(e)}`, { id: 'publish-failure' });
      return;
    }
    setConnectError(e);
  }, []);

  const onMediaDeviceFailure = useCallback(
    (failure?: MediaDeviceFailure, kind?: MediaDeviceKind) => {
      // Screen-share failures have no kind; the control bar reports those itself.
      if (kind !== 'audioinput' && kind !== 'videoinput') return;
      const cur = getMediaState(meetingId);
      onMediaChange(kind === 'audioinput' ? { ...cur, audioEnabled: false } : { ...cur, videoEnabled: false });
      toast.warning(deviceFailureMessage(failure, kind as MediaKind), { id: `media-failure-${kind}` });
    },
    [meetingId, onMediaChange],
  );

  const onDisconnected = useCallback(
    (reason?: DisconnectReason) => {
      connectedRef.current = false;
      if (reason === DisconnectReason.PARTICIPANT_REMOVED) finish('removed');
      else if (reason === DisconnectReason.ROOM_DELETED) finish('ended');
      else if (reason === DisconnectReason.CLIENT_INITIATED) {
        // Handled by our own leave/end flows.
      } else if (reason === DisconnectReason.DUPLICATE_IDENTITY) {
        setConnectError(new Error('You joined this meeting from another tab or device, so you were disconnected here.'));
      } else if (reason !== undefined) setConnectError(new Error(`Disconnected from the meeting (${DisconnectReason[reason] ?? reason}).`));
    },
    [finish],
  );

  // User-initiated only: fetches a fresh LiveKit token via join-status (never automatic after a fatal error).
  const reconnect = useCallback(() => {
    setConnectError(null);
    setStored(getStoredParticipant(meetingId));
    setJoin(null);
  }, [meetingId]);

  if (ended) {
    const canRejoin = ended === 'left' && (authStatus === 'authenticated' || !!joinCode);
    return <EndScreen reason={ended} meetingId={meetingId} onRejoin={canRejoin ? rejoin : undefined} />;
  }

  if (!join) {
    if (stored) {
      return (
        <ResumeJoin
          meetingId={meetingId}
          participantId={stored.participantId}
          waitToken={stored.waitToken}
          joinCode={stored.joinCode ?? joinCode}
          onResolved={onAdmitted}
          onFail={onResumeFail}
        />
      );
    }
    return <PreJoinForMember meetingId={meetingId} onAdmitted={onAdmitted} />;
  }

  if (!join.token || !join.livekitUrl) {
    return (
      <PublicShell>
        <div className="mx-auto w-full max-w-md py-12">
          <ErrorState error={new Error('The server did not return media credentials for this meeting.')} />
        </div>
      </PublicShell>
    );
  }

  const prefs = getDevicePrefs();
  const m = join.meeting;
  const audioOnly = m.mediaMode === 'AUDIO_ONLY';
  const startMuted = m.muteOnEntry && join.role !== 'HOST' && join.role !== 'CO_HOST';
  const wantAudio = media.audioEnabled && !startMuted;
  const wantVideo = media.videoEnabled && !audioOnly;
  // HOST_AND_SELF_ONLY attendee: may not publish in the stage room (server token); camera and microphone go to the
  // audience room, where only the stage can receive them.
  const audienceAttendee = !!join.audienceToken && join.role !== 'HOST' && join.role !== 'CO_HOST';
  const audienceMedia = audienceAttendee ? { audioEnabled: wantAudio, videoEnabled: wantVideo } : null;
  const options: RoomOptions = {
    adaptiveStream: true,
    dynacast: true,
    audioCaptureDefaults: { deviceId: prefs.audioDeviceId, echoCancellation: true, noiseSuppression: true },
    videoCaptureDefaults: { deviceId: prefs.videoDeviceId, resolution: { width: 1280, height: 720, frameRate: 30 } },
    audioOutput: prefs.audioOutputDeviceId ? { deviceId: prefs.audioOutputDeviceId } : undefined,
  };

  return (
    <LiveKitRoom
      key={join.token}
      serverUrl={join.livekitUrl}
      token={join.token}
      // After a fatal error/disconnect stay disconnected until the user clicks Reconnect.
      connect={!connectError}
      audio={audienceAttendee ? false : wantAudio}
      video={audienceAttendee ? false : wantVideo}
      options={options}
      data-lk-theme="default"
      className="h-dvh"
      onConnected={onConnected}
      onError={onError}
      onMediaDeviceFailure={onMediaDeviceFailure}
      onDisconnected={onDisconnected}
    >
      <RoomView
        meeting={m}
        join={join}
        finish={finish}
        connectError={connectError}
        onRetry={reconnect}
        onMediaChange={onMediaChange}
        audienceMedia={audienceMedia}
        roomOptions={options}
      />
    </LiveKitRoom>
  );
}
