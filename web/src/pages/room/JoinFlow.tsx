import { useEffect, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Circle, Clock, Hourglass, Loader2, ShieldX } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Alert, Card, CardContent, Input, Label } from '@/components/ui/primitives';
import { meetingsApi } from '@/lib/api/endpoints';
import { ApiError, errorMessage } from '@/lib/api/problem';
import type { JoinResponse, MediaMode, Meeting } from '@/lib/api/types';
import { clearJoin, getDevicePrefs, getRejoinCredentials, rememberGuestName, saveJoin, saveMediaState, setDevicePrefs, type DevicePrefs } from '@/lib/joinStore';
import { PreJoin } from './PreJoin';
import { isTerminalJoinStatusError } from './roomData';

export interface JoinTarget {
  meetingId: string;
  title: string;
  hostName?: string;
  mediaMode: MediaMode;
  passwordRequired: boolean;
  /** Anonymous guest: must provide a display name. */
  guest: boolean;
  defaultDisplayName?: string;
  waitingRoomEnabled?: boolean;
  /** The meeting records everyone automatically: say so before they join. */
  autoRecord?: boolean;
  /** A recording is running right now (e.g. started by the host): also said before joining. */
  recordingInProgress?: boolean;
}

/** Polls GET join-status every 2 s until the participant is admitted or denied (or a terminal error). */
export function WaitingRoom({
  meetingId,
  participantId,
  waitToken,
  title,
  waitingRoomEnabled,
  initialMeeting,
  onAdmitted,
  onCancel,
}: {
  meetingId: string;
  participantId: string;
  waitToken: string;
  title: string;
  waitingRoomEnabled?: boolean;
  initialMeeting?: Meeting;
  onAdmitted: (j: JoinResponse) => void;
  onCancel: () => void;
}) {
  const q = useQuery({
    queryKey: ['join-status', meetingId, participantId],
    queryFn: () => meetingsApi.joinStatus(meetingId, participantId, waitToken),
    refetchInterval: (query) =>
      (query.state.data && query.state.data.status !== 'WAITING') || isTerminalJoinStatusError(query.state.error) ? false : 2000,
    refetchIntervalInBackground: true,
    retry: (failureCount, error) => !isTerminalJoinStatusError(error) && failureCount < 2,
    gcTime: 0,
  });

  useEffect(() => {
    if (q.data?.status === 'ADMITTED') {
      // join-status may omit waitToken; keep the participant secret we already hold.
      const admitted = { ...q.data, waitToken: q.data.waitToken || waitToken };
      saveJoin(meetingId, admitted);
      onAdmitted(admitted);
    }
  }, [q.data, meetingId, waitToken, onAdmitted]);

  const leave = async () => {
    try {
      await meetingsApi.leave(meetingId, participantId, waitToken);
    } catch {
      // Leaving the waiting room is best-effort.
    }
    clearJoin(meetingId);
    onCancel();
  };

  if (q.isError && isTerminalJoinStatusError(q.error)) {
    const err = q.error as ApiError;
    const over = err.code === 'MEETING_ENDED' || err.code === 'MEETING_CANCELLED';
    return (
      <Card className="mx-auto w-full max-w-md">
        <CardContent className="flex flex-col items-center gap-3 p-8 text-center">
          <ShieldX className="size-10 text-destructive" aria-hidden="true" />
          <h2 className="text-lg font-semibold">
            {err.code === 'MEETING_CANCELLED' ? 'This meeting was cancelled' : err.code === 'MEETING_ENDED' ? 'This meeting has ended' : "You can't join right now"}
          </h2>
          <p role="alert" className="text-sm text-muted-foreground">
            {over ? `“${title}” is no longer running.` : errorMessage(err)}
          </p>
          <Button variant="outline" onClick={() => { clearJoin(meetingId); onCancel(); }}>
            Back
          </Button>
        </CardContent>
      </Card>
    );
  }

  if (q.data?.status === 'DENIED') {
    return (
      <Card className="mx-auto w-full max-w-md">
        <CardContent className="flex flex-col items-center gap-3 p-8 text-center">
          <ShieldX className="size-10 text-destructive" aria-hidden="true" />
          <h2 className="text-lg font-semibold">You weren't admitted</h2>
          <p className="text-sm text-muted-foreground">The host declined your request to join “{title}”.</p>
          <Button variant="outline" onClick={() => { clearJoin(meetingId); onCancel(); }}>
            Back
          </Button>
        </CardContent>
      </Card>
    );
  }

  // Before the first poll returns, fall back to the meeting info known at join time.
  const meeting = q.data?.meeting ?? initialMeeting;
  // joinBeforeHost=false and the host hasn't started → auto-admitted when the host joins.
  const showHostWait = meeting ? !meeting.joinBeforeHost && meeting.status !== 'LIVE' : waitingRoomEnabled === false;

  return (
    <Card className="mx-auto w-full max-w-md">
      <CardContent className="flex flex-col items-center gap-3 p-8 text-center" aria-live="polite">
        {showHostWait ? <Clock className="size-10 text-primary" aria-hidden="true" /> : <Hourglass className="size-10 text-primary" aria-hidden="true" />}
        <h2 className="text-lg font-semibold">
          {showHostWait ? 'Waiting for the host to start the meeting' : 'Waiting for the host to let you in'}
        </h2>
        <p className="text-sm text-muted-foreground">
          {showHostWait
            ? `You'll join “${title}” automatically as soon as the host arrives.`
            : `You're in the waiting room for “${title}”. Keep this page open.`}
        </p>
        <Loader2 className="size-5 animate-spin text-muted-foreground" aria-hidden="true" />
        {q.isError && (
          <p role="alert" className="text-sm text-destructive">
            {errorMessage(q.error)} — retrying…
          </p>
        )}
        <Button variant="outline" onClick={() => void leave()}>
          Leave waiting room
        </Button>
      </CardContent>
    </Card>
  );
}

/**
 * Pre-join (device test + name/password) → POST /join → waiting room (if needed) → onAdmitted.
 */
export function JoinFlow({ target, onAdmitted, onCancel }: { target: JoinTarget; onAdmitted: (j: JoinResponse) => void; onCancel?: () => void }) {
  const audioOnly = target.mediaMode === 'AUDIO_ONLY';
  const [prefs, setPrefs] = useState<DevicePrefs>(() => {
    const p = getDevicePrefs();
    return audioOnly ? { ...p, videoEnabled: false } : p;
  });
  const [displayName, setDisplayName] = useState(target.defaultDisplayName ?? '');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(target.passwordRequired);
  const [fieldErrors, setFieldErrors] = useState<{ displayName?: string; password?: string }>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [waiting, setWaiting] = useState<JoinResponse | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const fe: typeof fieldErrors = {};
    if (target.guest && !displayName.trim()) fe.displayName = 'Enter your name';
    if (showPassword && !password) fe.password = 'This meeting requires a password';
    setFieldErrors(fe);
    if (Object.keys(fe).length) return;
    setBusy(true);
    try {
      const res = await meetingsApi.join(target.meetingId, {
        password: password || undefined,
        displayName: displayName.trim() || undefined,
        rejoin: getRejoinCredentials(target.meetingId),
      });
      setDevicePrefs(prefs);
      if (target.guest && displayName.trim()) rememberGuestName(target.meetingId, displayName.trim());
      saveJoin(target.meetingId, res);
      // The pre-join choice is what gets published; later in-meeting toggles update it.
      saveMediaState(target.meetingId, { audioEnabled: prefs.audioEnabled, videoEnabled: prefs.videoEnabled && !audioOnly });
      if (res.status === 'ADMITTED') onAdmitted(res);
      else if (res.status === 'WAITING') setWaiting(res);
      else setError('The host declined your request to join.');
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.code === 'MEETING_PASSWORD_REQUIRED' || err.code === 'MEETING_PASSWORD_INVALID') {
          setShowPassword(true);
          setFieldErrors({ password: err.detail });
        } else {
          const nameErr = err.fieldErrors.find((f) => f.field === 'displayName');
          if (nameErr) setFieldErrors({ displayName: nameErr.message });
          setError(err.detail);
        }
      } else {
        setError(errorMessage(err));
      }
    } finally {
      setBusy(false);
    }
  };

  if (waiting) {
    return (
      <WaitingRoom
        meetingId={target.meetingId}
        participantId={waiting.participantId}
        waitToken={waiting.waitToken}
        title={target.title}
        waitingRoomEnabled={target.waitingRoomEnabled}
        initialMeeting={waiting.meeting}
        onAdmitted={onAdmitted}
        onCancel={() => setWaiting(null)}
      />
    );
  }

  return (
    <div className="mx-auto grid w-full max-w-5xl gap-6 lg:grid-cols-[1.4fr_1fr]">
      <PreJoin audioOnlyMeeting={audioOnly} displayName={displayName || target.defaultDisplayName} value={prefs} onChange={setPrefs} />
      <Card className="self-start">
        <CardContent className="p-6">
          <h1 className="text-xl font-semibold">{target.title}</h1>
          {target.hostName && <p className="mt-1 text-sm text-muted-foreground">Hosted by {target.hostName}</p>}
          {target.autoRecord ? (
            <Alert variant="warning" className="mt-4">
              <Circle className="fill-red-500 text-red-500" aria-hidden="true" />
              <span>
                <strong>This meeting is recorded.</strong> The meeting is recorded as a whole, and each time someone speaks their
                camera and microphone are recorded as a separate clip. By joining you agree to be recorded. If you join signed in, you
                can find your own clips under Recordings.
              </span>
            </Alert>
          ) : (
            target.recordingInProgress && (
              <Alert variant="warning" className="mt-4">
                <Circle className="fill-red-500 text-red-500" aria-hidden="true" />
                <span>
                  <strong>This meeting is being recorded.</strong> By joining you agree to be recorded.
                </span>
              </Alert>
            )
          )}
          <form onSubmit={(e) => void submit(e)} className="mt-6 grid gap-4" noValidate>
            {target.guest && (
              <div className="grid gap-1.5">
                <Label htmlFor="join-name">Your name</Label>
                <Input
                  id="join-name"
                  value={displayName}
                  maxLength={100}
                  autoComplete="name"
                  onChange={(e) => setDisplayName(e.target.value)}
                  aria-invalid={!!fieldErrors.displayName}
                  aria-describedby={fieldErrors.displayName ? 'join-name-err' : undefined}
                />
                {fieldErrors.displayName && <p id="join-name-err" className="text-xs text-destructive">{fieldErrors.displayName}</p>}
              </div>
            )}
            {showPassword && (
              <div className="grid gap-1.5">
                <Label htmlFor="join-pw">Meeting password</Label>
                <Input
                  id="join-pw"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  aria-invalid={!!fieldErrors.password}
                  aria-describedby={fieldErrors.password ? 'join-pw-err' : undefined}
                />
                {fieldErrors.password && <p id="join-pw-err" className="text-xs text-destructive">{fieldErrors.password}</p>}
              </div>
            )}
            {error && <Alert variant="destructive">{error}</Alert>}
            <Button type="submit" size="lg" loading={busy}>
              Join meeting
            </Button>
            {onCancel && (
              <Button type="button" variant="ghost" onClick={onCancel}>
                Cancel
              </Button>
            )}
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
