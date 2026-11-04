import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import {
  ArrowRight,
  CalendarClock,
  CalendarPlus,
  ChevronDown,
  Copy,
  Film,
  HardDrive,
  Lock,
  Home,
  Mic,
  Play,
  Plus,
  Radio,
  Repeat,
  Video,
  Zap,
  type LucideIcon,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge, Card, Input, Label, Skeleton } from '@/components/ui/primitives';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/overlays';
import { errorMessage } from '@/lib/api/problem';
import { EmptyState, ErrorState, ListSkeleton } from '@/components/common';
import { canStartOrJoin, copyJoinLink, useInstantMeeting } from '@/components/meetings';
import { SpeakingShare } from '@/components/SpeakingShare';
import { dashboardApi, meetingsApi, roomsApi, speechRecordingsApi } from '@/lib/api/endpoints';
import type { Meeting } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { browserTimeZone, formatDateTime, greeting } from '@/lib/time';
import { cn, extractJoinCode, formatBytes, formatDuration } from '@/lib/utils';

/** How many speaking-time meetings to show before "Show all". */
const SPEAKING_PREVIEW = 2;

export default function DashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const instant = useInstantMeeting();
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | null>(null);
  const [allSpeaking, setAllSpeaking] = useState(false);
  const [now] = useState(() => new Date());

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

  const firstName = user?.displayName.split(' ')[0];
  const next = upcoming.data?.items[0];
  const liveCount = live.data?.items.length ?? 0;
  const speaking = summary.data?.speakingHighlights ?? [];
  const shownSpeaking = allSpeaking ? speaking : speaking.slice(0, SPEAKING_PREVIEW);

  return (
    <div className="space-y-6">
      {/* ---- Welcome and the two things people come here for: start or join. */}
      <section className="relative overflow-hidden rounded-2xl border bg-card p-5 shadow-sm sm:p-7" aria-labelledby="dashboard-title">
        <div className="pointer-events-none absolute -right-24 -top-24 size-72 rounded-full bg-primary/10 blur-3xl" aria-hidden="true" />
        <div className="relative flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
          <div className="min-w-0">
            <p className="text-sm font-medium text-muted-foreground">
              {now.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })}
            </p>
            <h1 id="dashboard-title" className="mt-1 text-2xl font-semibold tracking-tight sm:text-3xl">
              {greeting(now.getHours())}
              {firstName ? `, ${firstName}` : ''}
            </h1>
            <p className="mt-1.5 text-sm text-muted-foreground">
              {liveCount > 0
                ? `${liveCount === 1 ? 'A meeting is' : `${liveCount} meetings are`} live right now.`
                : next?.startTime
                  ? `Next up: ${next.title}, ${formatDateTime(next.startTime, browserTimeZone())}.`
                  : 'Your schedule is clear. Start a meeting or plan one.'}
            </p>
          </div>
          <div className="flex w-full flex-col gap-2 sm:flex-row lg:w-auto">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button size="lg" className="h-11 shrink-0 rounded-xl px-5" loading={instant.isPending}>
                  {!instant.isPending && <Plus aria-hidden="true" />} New meeting <ChevronDown className="opacity-70" aria-hidden="true" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="w-64">
                <DropdownMenuItem onSelect={() => instant.mutate()}>
                  <Zap aria-hidden="true" />
                  <span className="grid">
                    <span className="font-medium">Start an instant meeting</span>
                    <span className="text-xs text-muted-foreground">Opens now; invite with a link</span>
                  </span>
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => navigate('/meetings/new')}>
                  <CalendarPlus aria-hidden="true" />
                  <span className="grid">
                    <span className="font-medium">Schedule for later</span>
                    <span className="text-xs text-muted-foreground">With invitations and a calendar invite</span>
                  </span>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
            <form onSubmit={onJoin} className="flex min-w-0 flex-1 gap-2 lg:w-80 lg:flex-none" noValidate>
              <Label htmlFor="join-code" className="sr-only">
                Meeting code or link
              </Label>
              <Input
                id="join-code"
                className="h-11 rounded-xl"
                placeholder="Enter a code or link"
                value={code}
                aria-invalid={!!codeError}
                aria-describedby={codeError ? 'join-code-err' : undefined}
                onChange={(e) => {
                  setCode(e.target.value);
                  setCodeError(null);
                }}
              />
              <Button type="submit" variant="outline" className="h-11 shrink-0 rounded-xl px-5">
                Join
              </Button>
            </form>
          </div>
        </div>
        {codeError && (
          <p id="join-code-err" className="relative mt-2 text-right text-xs text-destructive">
            {codeError}
          </p>
        )}
      </section>

      {/* ---- At a glance. */}
      <section className="grid grid-cols-2 gap-3 lg:grid-cols-4 lg:gap-4" aria-label="At a glance">
        <StatTile
          icon={CalendarClock}
          label="Upcoming"
          value={upcoming.data?.totalItems}
          loading={upcoming.isPending}
          hint="scheduled meetings"
          to="/meetings"
        />
        <StatTile
          icon={Radio}
          label="Live now"
          value={live.data?.items.length}
          loading={live.isPending}
          hint={liveCount > 0 ? 'join from the list below' : 'nothing live'}
          tone={liveCount > 0 ? 'live' : 'default'}
        />
        <StatTile
          icon={Mic}
          label="Speaking clips"
          value={summary.data?.speechClipCount}
          loading={summary.isPending}
          hint={summary.data && summary.data.recordingsInProgress > 0 ? `${summary.data.recordingsInProgress} recording now` : 'from your meetings'}
          to="/recordings"
        />
        <StatTile
          icon={HardDrive}
          label="Storage"
          value={summary.data ? formatBytes(summary.data.speechClipBytes + summary.data.storageUsedBytes) : undefined}
          loading={summary.isPending}
          hint="recordings and clips"
        />
      </section>

      {/* ---- Live meetings: only when there are any. */}
      {liveCount > 0 && (
        <section className="rounded-2xl border border-destructive/30 bg-destructive/5 p-4 sm:p-5" aria-labelledby="live-heading">
          <h2 id="live-heading" className="mb-3 flex items-center gap-2 text-sm font-semibold">
            <span className="relative flex size-2.5" aria-hidden="true">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-destructive opacity-60" />
              <span className="relative inline-flex size-2.5 rounded-full bg-destructive" />
            </span>
            Live now
          </h2>
          <ul className="grid grid-cols-[minmax(0,1fr)] gap-2">
            {live.data?.items.map((m) => (
              <MeetingRow key={m.id} meeting={m} />
            ))}
          </ul>
        </section>
      )}
      {live.isError && <ErrorState error={live.error} onRetry={() => void live.refetch()} />}

      <div className="grid gap-6 lg:grid-cols-3">
        {/* ---- Main column: up next and speaking time. */}
        <div className="grid content-start gap-6 lg:col-span-2">
          <Card className="overflow-hidden" role="region" aria-labelledby="upcoming-heading">
            <CardHead
              id="upcoming-heading"
              icon={CalendarClock}
              title="Up next"
              description="Your scheduled meetings"
              action={
                <Button variant="ghost" size="sm" asChild>
                  <Link to="/meetings">
                    View all <ArrowRight aria-hidden="true" />
                  </Link>
                </Button>
              }
            />
            <div className="p-3 sm:p-4">
              {upcoming.isPending ? (
                <ListSkeleton rows={3} />
              ) : upcoming.isError ? (
                <ErrorState error={upcoming.error} onRetry={() => void upcoming.refetch()} />
              ) : upcoming.data.items.length === 0 ? (
                <EmptyState
                  icon={CalendarPlus}
                  title="Nothing scheduled"
                  description="Schedule a meeting and it will show up here."
                  className="border-0 py-10"
                  action={
                    <Button asChild>
                      <Link to="/meetings/new">Schedule a meeting</Link>
                    </Button>
                  }
                />
              ) : (
                <ul className="grid grid-cols-[minmax(0,1fr)] gap-2">
                  {upcoming.data.items.map((m) => (
                    <MeetingRow key={m.id} meeting={m} />
                  ))}
                </ul>
              )}
            </div>
          </Card>
            <Card className="overflow-hidden" role="region" aria-labelledby="speaking-heading">
              <CardHead id="speaking-heading" icon={Mic} title="Speaking time" description="Share of speaking time in your recent meetings" />
              <div className="p-4">
                {summary.isPending ? (
                  <ListSkeleton rows={3} />
                ) : summary.isError ? (
                  <ErrorState error={summary.error} onRetry={() => void summary.refetch()} />
                ) : speaking.length === 0 ? (
                  <p className="text-sm text-muted-foreground">Speaking time appears here after meetings you host.</p>
                ) : (
                  <div className="grid gap-5">
                    {shownSpeaking.map((h) => (
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
                    {speaking.length > SPEAKING_PREVIEW && (
                      <Button variant="ghost" size="sm" className="justify-self-start" aria-expanded={allSpeaking} onClick={() => setAllSpeaking((v) => !v)}>
                        {allSpeaking ? 'Show less' : `Show all ${speaking.length} meetings`}
                      </Button>
                    )}
                  </div>
                )}
              </div>
            </Card>
        </div>

        {/* ---- Side column. */}
        <div className="grid content-start gap-6">
          <Card className="overflow-hidden" role="region" aria-labelledby="personal-heading">
            <CardHead id="personal-heading" icon={Home} title="Personal room" description="A permanent room with a fixed link" />
            <div className="p-4">
              {personal.isPending ? (
                <Skeleton className="h-16 w-full" />
              ) : personal.isError ? (
                <div className="grid gap-2">
                  <p role="alert" className="text-xs text-destructive">
                    {errorMessage(personal.error)}
                  </p>
                  <Button size="sm" variant="outline" onClick={() => void personal.refetch()}>
                    Retry
                  </Button>
                </div>
              ) : (
                <div className="grid gap-3">
                  <div className="flex items-center justify-between gap-2 rounded-xl border bg-muted/40 px-3 py-2.5">
                    <span className="text-xs text-muted-foreground">Room code</span>
                    <code className="font-mono text-sm font-semibold tracking-wide">{personal.data.joinCode}</code>
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    <Button onClick={() => navigate(`/meetings/${personal.data.id}/room`)}>
                      <Play aria-hidden="true" /> Start
                    </Button>
                    <Button variant="outline" onClick={() => void copyJoinLink(personal.data)} aria-label="Copy personal room link">
                      <Copy aria-hidden="true" /> Copy link
                    </Button>
                  </div>
                </div>
              )}
            </div>
          </Card>

          <Card className="overflow-hidden" role="region" aria-labelledby="recordings-heading">
            <CardHead
              id="recordings-heading"
              icon={Film}
              title="Recent recordings"
              description={summary.data ? `${summary.data.speechClipCount} speaking ${summary.data.speechClipCount === 1 ? 'clip' : 'clips'}` : 'Your latest recordings'}
              action={
                <Button variant="ghost" size="sm" asChild>
                  <Link to="/recordings">
                    All <ArrowRight aria-hidden="true" />
                  </Link>
                </Button>
              }
            />
            <div className="p-2">
              {clips.isPending || summary.isPending ? (
                <ListSkeleton rows={3} className="p-2" />
              ) : clips.isError ? (
                <ErrorState error={clips.error} onRetry={() => void clips.refetch()} />
              ) : summary.isError ? (
                <ErrorState error={summary.error} onRetry={() => void summary.refetch()} />
              ) : clips.data.items.length === 0 && meetingRecordings.length === 0 ? (
                <p className="p-3 text-sm text-muted-foreground">
                  No recordings yet. Turn on “Record automatically” for a meeting: each person’s speaking turns are saved here.
                </p>
              ) : (
                <ul aria-label="Recent recordings">
                  {meetingRecordings.map((r) => (
                    <RecordingRow
                      key={r.id}
                      icon={Film}
                      title={r.meetingTitle}
                      meta={`${formatDateTime(r.startedAt)} · ${formatDuration(r.durationSeconds)} · ${formatBytes(r.sizeBytes)}`}
                      badges={
                        <>
                          <Badge variant="secondary">{r.status.toLowerCase()}</Badge>
                          {r.layout === 'speaker' && <Badge variant="outline">active speaker</Badge>}
                          {r.stageOnly && <Badge variant="outline">hosts only</Badge>}
                        </>
                      }
                    />
                  ))}
                  {clips.data.items.map((c) => (
                    <RecordingRow
                      key={c.id}
                      icon={Mic}
                      title={
                        <>
                          {c.participantName}
                          <span className="font-normal text-muted-foreground"> · {c.meetingTitle}</span>
                        </>
                      }
                      meta={`${formatDateTime(c.startTime)} · ${formatDuration(c.durationMs == null ? undefined : Math.round(c.durationMs / 1000))} · ${formatBytes(c.fileSize)}`}
                      badges={c.status !== 'COMPLETED' ? <Badge variant="secondary">{c.status.toLowerCase()}</Badge> : undefined}
                    />
                  ))}
                </ul>
              )}
            </div>
          </Card>

        </div>
      </div>
    </div>
  );
}

/** Card header: icon, title, one-line description, optional action on the right. */
function CardHead({ id, icon: Icon, title, description, action }: { id: string; icon: LucideIcon; title: string; description?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex items-center gap-3 border-b px-4 py-3.5">
      <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary" aria-hidden="true">
        <Icon className="size-4.5" />
      </span>
      <div className="min-w-0 flex-1">
        <h2 id={id} className="truncate text-[15px] font-semibold tracking-tight">
          {title}
        </h2>
        {description && <p className="truncate text-xs text-muted-foreground">{description}</p>}
      </div>
      {action}
    </div>
  );
}

function StatTile({
  icon: Icon,
  label,
  value,
  hint,
  loading,
  to,
  tone = 'default',
}: {
  icon: LucideIcon;
  label: string;
  value: number | string | undefined;
  hint: string;
  loading?: boolean;
  to?: string;
  tone?: 'default' | 'live';
}) {
  const body = (
    <>
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</span>
        <span
          className={cn(
            'flex size-8 items-center justify-center rounded-lg',
            tone === 'live' ? 'bg-destructive/10 text-destructive' : 'bg-primary/10 text-primary',
          )}
          aria-hidden="true"
        >
          <Icon className="size-4" />
        </span>
      </div>
      {loading ? (
        <Skeleton className="mt-2 h-8 w-16" />
      ) : (
        <p className="mt-1.5 text-2xl font-semibold tabular-nums tracking-tight sm:text-3xl">{value ?? '–'}</p>
      )}
      <p className="mt-0.5 truncate text-xs text-muted-foreground">{hint}</p>
    </>
  );
  const cls = cn(
    'block rounded-2xl border bg-card p-4 shadow-sm transition-colors',
    tone === 'live' && 'border-destructive/30',
    to && 'hover:border-primary/40 hover:bg-accent/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
  );
  return to ? (
    <Link to={to} className={cls} aria-label={`${label}: ${loading ? 'loading' : (value ?? 'none')}. ${hint}`}>
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  );
}

/** A meeting on the dashboard: date block, title, time and type, and Start / Join / copy link. */
function MeetingRow({ meeting }: { meeting: Meeting }) {
  const navigate = useNavigate();
  const tz = browserTimeZone();
  const isHost = meeting.myRole === 'HOST' || meeting.myRole === 'CO_HOST';
  const start = meeting.startTime ? new Date(meeting.startTime) : null;
  const isLive = meeting.status === 'LIVE';
  return (
    <li className="flex min-w-0 items-center gap-3 rounded-xl border bg-card p-3 transition-colors hover:bg-accent/40 sm:gap-4">
      <div
        className={cn(
          'flex size-12 shrink-0 flex-col items-center justify-center rounded-lg leading-none',
          isLive ? 'bg-destructive/10 text-destructive' : 'bg-primary/10 text-primary',
        )}
        aria-hidden="true"
      >
        {start ? (
          <>
            <span className="text-[10px] font-semibold uppercase">{start.toLocaleDateString(undefined, { month: 'short', timeZone: tz })}</span>
            <span className="text-lg font-bold">{start.toLocaleDateString(undefined, { day: 'numeric', timeZone: tz })}</span>
          </>
        ) : meeting.mediaMode === 'AUDIO_ONLY' ? (
          <Mic className="size-5" />
        ) : (
          <Video className="size-5" />
        )}
      </div>
      <div className="min-w-0 flex-1">
        <Link to={`/meetings/${meeting.id}`} className="block truncate font-medium hover:underline focus-visible:underline focus-visible:outline-none">
          {meeting.title}
        </Link>
        <p className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
          {isLive && <Badge className="bg-destructive text-destructive-foreground">Live</Badge>}
          <span>{start ? formatDateTime(meeting.startTime, tz) : 'Instant meeting'}</span>
          <span className="inline-flex items-center gap-1">
            {meeting.mediaMode === 'AUDIO_ONLY' ? <Mic className="size-3" aria-hidden="true" /> : <Video className="size-3" aria-hidden="true" />}
            {meeting.mediaMode === 'AUDIO_ONLY' ? 'Voice only' : 'Video'}
          </span>
          {meeting.recurrenceRule && (
            <span className="inline-flex items-center gap-1">
              <Repeat className="size-3" aria-hidden="true" /> Recurring
            </span>
          )}
          {meeting.passwordProtected && (
            <span className="inline-flex items-center gap-1">
              <Lock className="size-3" aria-hidden="true" /> Password
            </span>
          )}
          {!isHost && (
            <>
              <span className="truncate">Host: {meeting.host.displayName}</span>
            </>
          )}
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-1.5">
        <Button variant="ghost" size="icon" aria-label={`Copy join link for ${meeting.title}`} onClick={() => void copyJoinLink(meeting)}>
          <Copy aria-hidden="true" />
        </Button>
        {canStartOrJoin(meeting) && (
          <Button size="sm" variant={isLive ? 'destructive' : 'default'} onClick={() => navigate(`/meetings/${meeting.id}/room`)}>
            {isLive ? 'Join' : isHost ? 'Start' : 'Join'}
          </Button>
        )}
      </div>
    </li>
  );
}

function RecordingRow({ icon: Icon, title, meta, badges }: { icon: LucideIcon; title: ReactNode; meta: string; badges?: ReactNode }) {
  return (
    <li className="flex items-start gap-3 rounded-lg px-2 py-2 transition-colors hover:bg-accent/40">
      <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground" aria-hidden="true">
        <Icon className="size-4" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{title}</p>
        <p className="truncate text-xs text-muted-foreground">{meta}</p>
      </div>
      {badges && <div className="flex shrink-0 flex-wrap justify-end gap-1">{badges}</div>}
    </li>
  );
}
