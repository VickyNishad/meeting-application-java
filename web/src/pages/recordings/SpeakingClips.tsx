import { useEffect, useMemo, useRef, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { CalendarDays, Download, Loader2, Mic, Play } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Badge, Card, NativeSelect } from '@/components/ui/primitives';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/overlays';
import { EmptyState, ErrorState, ListSkeleton, Pagination } from '@/components/common';
import { speechRecordingsApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';
import type { SpeechRecording, SpeechRecordingView } from '@/lib/api/types';
import { browserTimeZone, dateKeyInZone, formatDateTime } from '@/lib/time';
import { cn, formatBytes, formatDuration } from '@/lib/utils';

function statusVariant(s: SpeechRecording['status']) {
  if (s === 'COMPLETED') return 'success' as const;
  if (s === 'FAILED') return 'destructive' as const;
  if (s === 'RECORDING') return 'live' as const;
  return 'secondary' as const;
}

const playable = (c: SpeechRecording) => c.status === 'COMPLETED' || c.status === 'INCOMPLETE';
/** YYYY-MM-DD (the API's date) as DD-MM-YYYY (how dates are shown, like the clip folders). */
const dmy = (d: string) => d.split('-').reverse().join('-');

/**
 * A calendar for any date, next to the date dropdown (which lists only dates that have clips). Opens the browser's
 * own date picker; the input sits under the button so the picker appears there.
 */
function DatePickerButton({ value, onChange }: { value: string; onChange: (date: string) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const open = () => {
    const el = input.current;
    if (!el) return;
    try {
      el.showPicker();
    } catch {
      el.focus(); // browsers without showPicker(): the focused date field takes keyboard input
    }
  };
  return (
    <div className="relative shrink-0">
      <input
        ref={input}
        type="date"
        tabIndex={-1}
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 size-full opacity-0"
        value={value}
        max={dateKeyInZone(new Date(), browserTimeZone())}
        onChange={(e) => e.target.value && onChange(e.target.value)}
      />
      <Button type="button" size="icon" variant="outline" aria-label="Choose a date from the calendar" title="Choose a date" onClick={open}>
        <CalendarDays aria-hidden="true" />
      </Button>
    </div>
  );
}
const seconds = (ms?: number | null) => (ms == null ? undefined : Math.round(ms / 1000));

function ClipPlayer({ clip, onClose }: { clip: SpeechRecording | null; onClose: () => void }) {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!clip) return;
    let cancelled = false;
    setUrl(null);
    setError(null);
    speechRecordingsApi
      .accessUrl(clip.id, false)
      .then((r) => !cancelled && setUrl(r.url))
      .catch((e: unknown) => !cancelled && setError(errorMessage(e)));
    return () => {
      cancelled = true;
    };
  }, [clip]);
  const video = clip?.fileFormat.startsWith('video/');
  const onMediaError = () => setError('The clip could not be played. The link may have expired: close and reopen to get a new one.');
  return (
    <Dialog open={clip !== null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>
            {clip?.participantName}
            <span className="font-normal text-muted-foreground"> · {clip?.meetingTitle}</span>
          </DialogTitle>
          <DialogDescription>
            {formatDateTime(clip?.startTime)} · {formatDuration(seconds(clip?.durationMs))}
          </DialogDescription>
        </DialogHeader>
        <div className={cn('flex items-center justify-center overflow-hidden rounded-lg bg-black', video ? 'aspect-video' : 'p-6')}>
          {error ? (
            <p role="alert" className="p-4 text-center text-sm text-red-300">
              {error}
            </p>
          ) : url ? (
            video ? (
              <video src={url} controls autoPlay className="size-full" onError={onMediaError}>
                Your browser cannot play this clip.
              </video>
            ) : (
              <audio src={url} controls autoPlay className="w-full" onError={onMediaError}>
                Your browser cannot play this clip.
              </audio>
            )
          ) : (
            <Loader2 className="size-6 animate-spin text-neutral-400" aria-label="Loading clip" />
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Speaking clips: one file per participant per speaking turn. The host sees every clip of the meetings it hosts; a
 * participant sees its own. Filters offer only values from clips this user may see; files open through short-lived
 * signed links issued by the server after its access check.
 */
export function SpeakingClips({ className }: { className?: string }) {
  const [view, setView] = useState<SpeechRecordingView>('hosted');
  const [date, setDate] = useState('');
  const [meetingId, setMeetingId] = useState('');
  const [participantId, setParticipantId] = useState('');
  const [page, setPage] = useState(0);
  const [playing, setPlaying] = useState<SpeechRecording | null>(null);
  const [downloading, setDownloading] = useState<string | null>(null);

  const facets = useQuery({ queryKey: ['speech-facets', view], queryFn: () => speechRecordingsApi.facets(view) });
  const filters = { view, date: date || undefined, meetingId: meetingId || undefined, participantId: participantId || undefined };
  const q = useQuery({
    queryKey: ['speech-recordings', filters, page],
    queryFn: () => speechRecordingsApi.list({ ...filters, page, size: 20 }),
    placeholderData: keepPreviousData,
  });

  const switchView = (v: SpeechRecordingView) => {
    setView(v);
    setDate('');
    setMeetingId('');
    setParticipantId('');
    setPage(0);
  };
  const filter = (set: (v: string) => void) => (e: React.ChangeEvent<HTMLSelectElement>) => {
    set(e.target.value);
    setPage(0);
  };
  const pickDate = (d: string) => {
    setDate(d);
    setPage(0);
  };
  // The participant filter follows the meeting filter: a participant is one participation in one meeting.
  const allParticipants = facets.data?.participants;
  const participantOptions = useMemo(
    () => (meetingId ? allParticipants?.filter((p) => p.meetingId === meetingId) : allParticipants) ?? [],
    [allParticipants, meetingId],
  );
  const changeMeeting = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const next = e.target.value;
    setMeetingId(next);
    // A selected participant from another meeting would only ever match nothing: clear it.
    if (participantId && next && !allParticipants?.some((p) => p.id === participantId && p.meetingId === next)) {
      setParticipantId('');
    }
    setPage(0);
  };

  const download = async (c: SpeechRecording) => {
    setDownloading(c.id);
    try {
      const { url } = await speechRecordingsApi.accessUrl(c.id, true);
      const a = document.createElement('a');
      a.href = url;
      a.rel = 'noopener';
      document.body.appendChild(a);
      a.click();
      a.remove();
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setDownloading(null);
    }
  };

  return (
    <section className={className} aria-labelledby="speaking-clips-heading">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 id="speaking-clips-heading" className="text-lg font-semibold">
            Speaking clips
          </h2>
          <p className="text-sm text-muted-foreground">One clip per person each time they speak, in meetings recorded automatically.</p>
        </div>
        <div className="inline-flex rounded-md border p-0.5" role="group" aria-label="Whose clips">
          {(['hosted', 'mine'] as const).map((v) => (
            <Button key={v} size="sm" variant={view === v ? 'default' : 'ghost'} aria-pressed={view === v} onClick={() => switchView(v)}>
              {v === 'hosted' ? 'Meetings I host' : 'My clips'}
            </Button>
          ))}
        </div>
      </div>

      <div className="mt-4 grid gap-2 sm:grid-cols-3">
        <div className="flex gap-2">
          <NativeSelect aria-label="Filter by date" value={date} onChange={filter(setDate)} className="min-w-0 flex-1">
            <option value="">All dates</option>
            {/* A date picked in the calendar that has no clips is still shown as the selection. */}
            {date && !facets.data?.dates.includes(date) && <option value={date}>{dmy(date)} (custom)</option>}
            {facets.data?.dates.map((d) => (
              <option key={d} value={d}>
                {dmy(d)}
              </option>
            ))}
          </NativeSelect>
          <DatePickerButton value={date} onChange={pickDate} />
        </div>
        <NativeSelect aria-label="Filter by meeting" value={meetingId} onChange={changeMeeting}>
          <option value="">All meetings</option>
          {facets.data?.meetings.map((m) => (
            <option key={m.id} value={m.id}>
              {m.label}
            </option>
          ))}
        </NativeSelect>
        <NativeSelect
          aria-label="Filter by participant"
          value={participantId}
          onChange={filter(setParticipantId)}
          disabled={facets.isPending || (!!meetingId && participantOptions.length === 0)}
        >
          {facets.isPending ? (
            <option value="">Loading participants…</option>
          ) : meetingId && participantOptions.length === 0 ? (
            <option value="">No participants in this meeting</option>
          ) : (
            <option value="">All participants</option>
          )}
          {participantOptions.map((p) => (
            <option key={p.id} value={p.id}>
              {p.label}
            </option>
          ))}
        </NativeSelect>
      </div>
      {facets.isError && (
        <p role="alert" className="mt-2 flex flex-wrap items-center gap-2 text-sm text-destructive">
          The filter options could not be loaded: {errorMessage(facets.error)}
          <Button size="sm" variant="outline" onClick={() => void facets.refetch()}>
            Retry
          </Button>
        </p>
      )}

      <div className="mt-4">
        {q.isPending ? (
          <ListSkeleton rows={3} />
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : q.data.items.length === 0 ? (
          <EmptyState icon={Mic} title="No speaking clips" description="Clips are recorded in meetings with automatic recording turned on." />
        ) : (
          <>
            <ul className="grid gap-2" aria-label="Speaking clips">
              {q.data.items.map((c) => (
                <li key={c.id}>
                  <Card className="flex flex-col gap-3 p-3 sm:flex-row sm:items-center">
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{c.participantName}</span>
                        <span className="truncate text-sm text-muted-foreground">{c.meetingTitle}</span>
                        <Badge variant={statusVariant(c.status)}>{c.status.toLowerCase()}</Badge>
                      </div>
                      <p className="mt-0.5 text-xs text-muted-foreground">
                        {formatDateTime(c.startTime)} · {formatDuration(seconds(c.durationMs))} · {formatBytes(c.fileSize)} · {c.fileName}
                      </p>
                      {c.failureReason && <p className="mt-0.5 text-xs text-destructive">{c.failureReason}</p>}
                    </div>
                    <div className="flex gap-2">
                      <Button size="sm" onClick={() => setPlaying(c)} disabled={!playable(c)} aria-label={`Play clip of ${c.participantName}, ${formatDateTime(c.startTime)}`}>
                        <Play aria-hidden="true" /> Play
                      </Button>
                      <Button
                        size="icon"
                        variant="outline"
                        aria-label={`Download clip of ${c.participantName}, ${formatDateTime(c.startTime)}`}
                        onClick={() => void download(c)}
                        loading={downloading === c.id}
                        disabled={!playable(c)}
                      >
                        {downloading !== c.id && <Download aria-hidden="true" />}
                      </Button>
                    </div>
                  </Card>
                </li>
              ))}
            </ul>
            <Pagination page={q.data.page} totalPages={q.data.totalPages} onPageChange={setPage} />
          </>
        )}
      </div>
      <ClipPlayer clip={playing} onClose={() => setPlaying(null)} />
    </section>
  );
}
