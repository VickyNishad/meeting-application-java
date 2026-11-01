import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { CalendarPlus, Copy, DoorOpen, Film, Home, Mic, Play, Radio, Video, Zap } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Input, Label, Skeleton } from '@/components/ui/primitives';
import { errorMessage } from '@/lib/api/problem';
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/common';
import { MeetingCard, copyJoinLink, useInstantMeeting } from '@/components/meetings';
import { SpeakingShare } from '@/components/SpeakingShare';
import { dashboardApi, meetingsApi, roomsApi, speechRecordingsApi } from '@/lib/api/endpoints';
import { useAuth } from '@/lib/auth/AuthProvider';
import { formatDateTime } from '@/lib/time';
import { extractJoinCode, formatBytes, formatDuration } from '@/lib/utils';

export default function DashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const instant = useInstantMeeting();
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | null>(null);

  const live = useQuery({ queryKey: ['meetings', 'live', 0], queryFn: () => meetingsApi.list('live', 0, 10) });
  const upcoming = useQuery({ queryKey: ['meetings', 'upcoming', 0, 5], queryFn: () => meetingsApi.list('upcoming', 0, 5) });
  const personal = useQuery({ queryKey: ['rooms', 'personal'], queryFn: roomsApi.personal });
  const summary = useQuery({ queryKey: ['dashboard', 'summary'], queryFn: dashboardApi.summary });
  const clips = useQuery({
    queryKey: ['speech-recordings', 'dashboard'],
    queryFn: () => speechRecordingsApi.list({ view: 'hosted', page: 0, size: 5 }),
  });
  // Whole-meeting (cloud) recordings that did not fail; failed ones are listed on the Recordings page only.
  const meetingRecordings = (summary.data?.recentRecordings ?? []).filter((r) => r.status !== 'FAILED');

  const onJoin = (e: FormEvent) => {
    e.preventDefault();
    const c = extractJoinCode(code);
    if (!c) {
      setCodeError('Enter a meeting code or link');
      return;
    }
    navigate(`/j/${encodeURIComponent(c)}`);
  };

  return (
    <div>
      <PageHeader
        title={`Hi${user ? `, ${user.displayName.split(' ')[0]}` : ''}`}
        description="Start a meeting, join one, or check what's coming up."
      />

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <Card className="border-primary/30 bg-gradient-to-br from-primary/10 to-transparent">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Zap className="size-4 text-primary" aria-hidden="true" /> New meeting
            </CardTitle>
            <CardDescription>Start an instant meeting and invite people with a link.</CardDescription>
          </CardHeader>
          <CardContent>
            <Button className="w-full" onClick={() => instant.mutate()} loading={instant.isPending}>
              <Video aria-hidden="true" /> Start now
            </Button>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <DoorOpen className="size-4 text-primary" aria-hidden="true" /> Join a meeting
            </CardTitle>
            <CardDescription>Paste a code or a join link.</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={onJoin} className="flex gap-2" noValidate>
              <Label htmlFor="join-code" className="sr-only">
                Meeting code or link
              </Label>
              <Input
                id="join-code"
                placeholder="abc-defg-hij"
                value={code}
                aria-invalid={!!codeError}
                aria-describedby={codeError ? 'join-code-err' : undefined}
                onChange={(e) => {
                  setCode(e.target.value);
                  setCodeError(null);
                }}
              />
              <Button type="submit" variant="secondary">
                Join
              </Button>
            </form>
            {codeError && (
              <p id="join-code-err" className="mt-1 text-xs text-destructive">
                {codeError}
              </p>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <CalendarPlus className="size-4 text-primary" aria-hidden="true" /> Schedule
            </CardTitle>
            <CardDescription>Plan a meeting with invitations and a calendar invite.</CardDescription>
          </CardHeader>
          <CardContent>
            <Button variant="outline" className="w-full" asChild>
              <Link to="/meetings/new">Schedule a meeting</Link>
            </Button>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Home className="size-4 text-primary" aria-hidden="true" /> My personal room
            </CardTitle>
            <CardDescription>
              {personal.data ? (
                <>
                  Your permanent room · code <code className="text-foreground">{personal.data.joinCode}</code>
                </>
              ) : (
                'A permanent meeting room with a fixed link.'
              )}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {personal.isPending ? (
              <Skeleton className="h-9 w-full" />
            ) : personal.isError ? (
              <div className="grid gap-2">
                <p role="alert" className="text-xs text-destructive">{errorMessage(personal.error)}</p>
                <Button size="sm" variant="outline" onClick={() => void personal.refetch()}>
                  Retry
                </Button>
              </div>
            ) : (
              <div className="flex gap-2">
                <Button className="flex-1" onClick={() => navigate(`/meetings/${personal.data.id}/room`)}>
                  <Play aria-hidden="true" /> Start
                </Button>
                <Button variant="outline" onClick={() => void copyJoinLink(personal.data)} aria-label="Copy personal room link">
                  <Copy aria-hidden="true" /> Copy link
                </Button>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <section className="mt-8" aria-labelledby="live-heading">
        <h2 id="live-heading" className="mb-3 flex items-center gap-2 text-lg font-semibold">
          <Radio className="size-4 text-destructive" aria-hidden="true" /> Live now
        </h2>
        {live.isPending ? (
          <ListSkeleton rows={1} />
        ) : live.isError ? (
          <ErrorState error={live.error} onRetry={() => void live.refetch()} />
        ) : live.data.items.length === 0 ? (
          <p className="rounded-xl border border-dashed p-4 text-sm text-muted-foreground">No meetings are live right now.</p>
        ) : (
          <div className="grid gap-3">
            {live.data.items.map((m) => (
              <MeetingCard key={m.id} meeting={m} />
            ))}
          </div>
        )}
      </section>

      <section className="mt-8 grid gap-4 lg:grid-cols-2" aria-label="Recordings and speaking time">
        <Card>
          <CardHeader className="flex-row items-start justify-between gap-2">
            <div>
              <CardTitle className="flex items-center gap-2">
                <Film className="size-4 text-primary" aria-hidden="true" /> Recent recordings
              </CardTitle>
              <CardDescription>
                {summary.data
                  ? `${summary.data.speechClipCount} speaking ${summary.data.speechClipCount === 1 ? 'clip' : 'clips'} · ${formatBytes(
                      summary.data.speechClipBytes + summary.data.storageUsedBytes,
                    )} stored for your meetings${
                      summary.data.recordingsInProgress > 0 ? ` · ${summary.data.recordingsInProgress} recording now` : ''
                    }`
                  : 'Your latest recordings.'}
              </CardDescription>
            </div>
            <Button variant="link" size="sm" asChild>
              <Link to="/recordings">All recordings</Link>
            </Button>
          </CardHeader>
          <CardContent>
            {clips.isPending || summary.isPending ? (
              <ListSkeleton rows={3} />
            ) : clips.isError ? (
              <ErrorState error={clips.error} onRetry={() => void clips.refetch()} />
            ) : summary.isError ? (
              <ErrorState error={summary.error} onRetry={() => void summary.refetch()} />
            ) : clips.data.items.length === 0 && meetingRecordings.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No recordings yet. Turn on “Record automatically” for a meeting: each person’s speaking turns are saved here.
              </p>
            ) : (
              <ul className="grid gap-2" aria-label="Recent recordings">
                {clips.data.items.map((c) => (
                  <li key={c.id} className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
                    <span className="min-w-0 flex-1 truncate">
                      <span className="font-medium">{c.participantName}</span>
                      <span className="text-muted-foreground"> · {c.meetingTitle}</span>
                    </span>
                    {c.status !== 'COMPLETED' && <Badge variant="secondary">{c.status.toLowerCase()}</Badge>}
                    <span className="w-full text-xs text-muted-foreground">
                      {formatDateTime(c.startTime)} · {formatDuration(c.durationMs == null ? undefined : Math.round(c.durationMs / 1000))} ·{' '}
                      {formatBytes(c.fileSize)}
                    </span>
                  </li>
                ))}
                {meetingRecordings.map((r) => (
                  <li key={r.id} className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
                    <span className="min-w-0 flex-1 truncate font-medium">{r.meetingTitle}</span>
                    <Badge variant="secondary">{r.status.toLowerCase()}</Badge>
                    {r.layout === 'speaker' && <Badge variant="outline">active speaker</Badge>}
                    {r.stageOnly && <Badge variant="outline">hosts only</Badge>}
                    <span className="w-full text-xs text-muted-foreground">
                      {formatDateTime(r.startedAt)} · {formatDuration(r.durationSeconds)} · {formatBytes(r.sizeBytes)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Mic className="size-4 text-primary" aria-hidden="true" /> Speaking time
            </CardTitle>
            <CardDescription>Share of speaking time in your recent meetings.</CardDescription>
          </CardHeader>
          <CardContent>
            {summary.isPending ? (
              <ListSkeleton rows={3} />
            ) : summary.isError ? (
              <ErrorState error={summary.error} onRetry={() => void summary.refetch()} />
            ) : summary.data.speakingHighlights.length === 0 ? (
              <p className="text-sm text-muted-foreground">Speaking time appears here after meetings you host.</p>
            ) : (
              <div className="grid gap-5">
                {summary.data.speakingHighlights.map((h) => (
                  <div key={h.meetingId}>
                    <p className="mb-2 flex items-baseline justify-between gap-2 text-sm">
                      <Link to={`/meetings/${h.meetingId}`} className="truncate font-medium hover:underline">
                        {h.title}
                      </Link>
                      <span className="shrink-0 text-xs text-muted-foreground">{formatDuration(Math.round(h.totalSpeakingMs / 1000))} total</span>
                    </p>
                    <SpeakingShare
                      label={`Speaking time in ${h.title}`}
                      speakers={h.topSpeakers.map((s) => ({ name: s.displayName, speakingMs: s.speakingMs, sharePercent: s.sharePercent }))}
                    />
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </section>

      <section className="mt-8" aria-labelledby="upcoming-heading">
        <div className="mb-3 flex items-center justify-between">
          <h2 id="upcoming-heading" className="text-lg font-semibold">
            Upcoming
          </h2>
          <Button variant="link" asChild>
            <Link to="/meetings">View all</Link>
          </Button>
        </div>
        {upcoming.isPending ? (
          <ListSkeleton rows={3} />
        ) : upcoming.isError ? (
          <ErrorState error={upcoming.error} onRetry={() => void upcoming.refetch()} />
        ) : upcoming.data.items.length === 0 ? (
          <EmptyState
            icon={CalendarPlus}
            title="Nothing scheduled"
            description="Schedule a meeting and it will show up here."
            action={
              <Button asChild>
                <Link to="/meetings/new">Schedule a meeting</Link>
              </Button>
            }
          />
        ) : (
          <div className="grid gap-3">
            {upcoming.data.items.map((m) => (
              <MeetingCard key={m.id} meeting={m} />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
