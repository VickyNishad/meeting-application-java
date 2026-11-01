import { useCallback } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { CalendarX2, LogIn } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/primitives';
import { ErrorState } from '@/components/common';
import { FullPageSpinner } from '@/lib/auth/guards';
import { meetingsApi } from '@/lib/api/endpoints';
import { useAuth } from '@/lib/auth/AuthProvider';
import { JoinFlow } from '../room/JoinFlow';
import { formatDateTime } from '@/lib/time';

export default function JoinByCodePage() {
  const { joinCode = '' } = useParams();
  const navigate = useNavigate();
  const { status, user } = useAuth();
  const q = useQuery({ queryKey: ['by-code', joinCode], queryFn: () => meetingsApi.byCode(joinCode), enabled: status !== 'loading' });

  const onAdmitted = useCallback(
    (j: { meeting: { id: string } }) => navigate(`/meetings/${j.meeting.id}/room`, { replace: true }),
    [navigate],
  );

  if (status === 'loading' || q.isPending) return <FullPageSpinner label="Looking up meeting…" />;
  if (q.isError) {
    return (
      <div className="mx-auto w-full max-w-md py-12">
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      </div>
    );
  }
  const info = q.data;
  const next = `/j/${encodeURIComponent(joinCode)}`;

  if (info.status === 'ENDED' || info.status === 'CANCELLED') {
    return (
      <Card className="mx-auto mt-12 w-full max-w-md">
        <CardContent className="flex flex-col items-center gap-3 p-8 text-center">
          <CalendarX2 className="size-10 text-muted-foreground" aria-hidden="true" />
          <h1 className="text-lg font-semibold">{info.title}</h1>
          <p className="text-sm text-muted-foreground">This meeting has {info.status === 'ENDED' ? 'ended' : 'been cancelled'}.</p>
        </CardContent>
      </Card>
    );
  }

  const anonymous = status !== 'authenticated';
  if (anonymous && !info.allowGuests) {
    return (
      <Card className="mx-auto mt-12 w-full max-w-md">
        <CardContent className="flex flex-col items-center gap-3 p-8 text-center">
          <LogIn className="size-10 text-primary" aria-hidden="true" />
          <h1 className="text-lg font-semibold">{info.title}</h1>
          <p className="text-sm text-muted-foreground">Sign in to join this meeting — guests aren't allowed.</p>
          <div className="flex gap-2">
            <Button asChild>
              <Link to={`/login?next=${encodeURIComponent(next)}`}>Sign in</Link>
            </Button>
            <Button variant="outline" asChild>
              <Link to={`/register?next=${encodeURIComponent(next)}`}>Create account</Link>
            </Button>
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="py-6">
      {info.status === 'SCHEDULED' && info.startTime && (
        <p className="mx-auto mb-4 max-w-5xl text-sm text-muted-foreground">Scheduled for {formatDateTime(info.startTime)}</p>
      )}
      <JoinFlow
        target={{
          meetingId: info.meetingId,
          title: info.title,
          hostName: info.hostName,
          mediaMode: info.mediaMode,
          passwordRequired: info.passwordRequired,
          guest: anonymous,
          defaultDisplayName: user?.displayName,
          waitingRoomEnabled: info.waitingRoomEnabled,
          autoRecord: info.autoRecord,
          recordingInProgress: info.recordingInProgress,
        }}
        onAdmitted={onAdmitted}
      />
      {anonymous && (
        <p className="mt-6 text-center text-sm text-muted-foreground">
          Have an account?{' '}
          <Link className="text-primary hover:underline" to={`/login?next=${encodeURIComponent(next)}`}>
            Sign in
          </Link>{' '}
          to join as yourself.
        </p>
      )}
    </div>
  );
}
