import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, Film, Loader2, Play, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Badge, Card } from '@/components/ui/primitives';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/overlays';
import { ConfirmDialog, EmptyState, ErrorState, ListSkeleton, PageHeader, Pagination } from '@/components/common';
import { recordingsApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';
import type { Recording } from '@/lib/api/types';
import { recordingSubject } from '@/lib/recordings';
import { formatDate, formatDateTime } from '@/lib/time';
import { formatBytes, formatDuration } from '@/lib/utils';
import { SpeakingClips } from './SpeakingClips';

function statusVariant(s: Recording['status']) {
  if (s === 'AVAILABLE') return 'success' as const;
  if (s === 'FAILED') return 'destructive' as const;
  if (s === 'ACTIVE' || s === 'STARTING') return 'live' as const;
  return 'secondary' as const;
}

function Player({ recording, onClose }: { recording: Recording | null; onClose: () => void }) {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!recording) return;
    let cancelled = false;
    setUrl(null);
    setError(null);
    recordingsApi
      .playbackUrl(recording.id)
      .then((r) => !cancelled && setUrl(r.url))
      .catch((e: unknown) => !cancelled && setError(errorMessage(e)));
    return () => {
      cancelled = true;
    };
  }, [recording]);
  return (
    <Dialog open={recording !== null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-4xl">
        <DialogHeader>
          <DialogTitle>
            {recording?.meetingTitle}
            {recording && <span className="font-normal text-muted-foreground"> · {recordingSubject(recording)}</span>}
          </DialogTitle>
          <DialogDescription>
            {formatDateTime(recording?.startedAt)} · {formatDuration(recording?.durationSeconds)}
          </DialogDescription>
        </DialogHeader>
        <div className="flex aspect-video items-center justify-center overflow-hidden rounded-lg bg-black">
          {error ? (
            <p role="alert" className="p-4 text-center text-sm text-red-300">
              {error}
            </p>
          ) : url ? (
            <video src={url} controls autoPlay className="size-full" onError={() => setError('The video could not be played. The link may have expired — close and reopen to get a new one.')}>
              Your browser cannot play this recording.
            </video>
          ) : (
            <Loader2 className="size-6 animate-spin text-neutral-400" aria-label="Loading video" />
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

export default function RecordingsPage() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [playing, setPlaying] = useState<Recording | null>(null);
  const [deleting, setDeleting] = useState<Recording | null>(null);
  const [downloading, setDownloading] = useState<string | null>(null);
  const q = useQuery({ queryKey: ['recordings', page], queryFn: () => recordingsApi.library(page, 20), placeholderData: keepPreviousData });
  // Folded away while every recording on the first page failed (e.g. no recording service): the speaking clips
  // above are then what is worth seeing. The viewer can always open them.
  const [showComposite, setShowComposite] = useState<boolean | null>(null);
  const allFailed = page === 0 && !!q.data && q.data.items.length > 0 && q.data.items.every((r) => r.status === 'FAILED');
  const compositeOpen = showComposite ?? !allFailed;

  const download = async (r: Recording) => {
    setDownloading(r.id);
    try {
      const { url } = await recordingsApi.downloadUrl(r.id);
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
    <div>
      <PageHeader title="Recordings" description="Recordings of meetings you host, and your own recordings from meetings you joined." />
      <SpeakingClips />
      <section className="mt-10" aria-labelledby="meeting-recordings-heading">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 id="meeting-recordings-heading" className="text-lg font-semibold">
              Whole-meeting recordings
            </h2>
            <p className="text-sm text-muted-foreground">One video of the whole meeting, made by the media server’s recording service.</p>
          </div>
          {q.data && q.data.items.length > 0 && (
            <Button variant="outline" size="sm" aria-expanded={compositeOpen} aria-controls="meeting-recordings-list" onClick={() => setShowComposite(!compositeOpen)}>
              {compositeOpen ? 'Hide' : `Show ${q.data.totalItems}`}
            </Button>
          )}
        </div>
      {q.isPending ? (
        <ListSkeleton rows={4} />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : q.data.items.length === 0 ? (
        <EmptyState icon={Film} title="No recordings yet" description="Start a cloud recording from the host controls during a meeting." />
      ) : !compositeOpen ? (
        <p className="rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
          {q.data.totalItems} {q.data.totalItems === 1 ? 'recording' : 'recordings'}, none of the latest could be saved:{' '}
          {q.data.items[0]?.failureReason ?? 'the recording service failed.'} They are hidden; nothing was deleted.
        </p>
      ) : (
        <div id="meeting-recordings-list">
          <div className="grid gap-3">
            {q.data.items.map((r) => (
              <Card key={r.id} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
                <div className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary" aria-hidden="true">
                  <Film className="size-5" />
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Link to={`/meetings/${r.meetingId}`} className="truncate font-medium hover:underline">
                      {r.meetingTitle}
                    </Link>
                    <Badge variant="secondary">{recordingSubject(r)}</Badge>
                    <Badge variant={statusVariant(r.status)}>{r.status.toLowerCase()}</Badge>
                    {r.layout === 'speaker' && <Badge variant="outline">active speaker</Badge>}
                    {r.stageOnly && <Badge variant="outline" title="Recorded in a webinar: shows the host and co-hosts only">hosts only</Badge>}
                  </div>
                  <p className="mt-1 text-sm text-muted-foreground">
                    {formatDateTime(r.startedAt)} · {formatDuration(r.durationSeconds)} · {formatBytes(r.sizeBytes)}
                    {r.retentionUntil && <> · kept until {formatDate(r.retentionUntil)}</>}
                  </p>
                  {r.failureReason && <p className="mt-1 text-xs text-destructive">{r.failureReason}</p>}
                </div>
                <div className="flex gap-2">
                  <Button size="sm" onClick={() => setPlaying(r)} disabled={r.status !== 'AVAILABLE'}>
                    <Play aria-hidden="true" /> Play
                  </Button>
                  <Button size="icon" variant="outline" aria-label={`Download recording of ${r.meetingTitle}`} onClick={() => void download(r)} loading={downloading === r.id} disabled={r.status !== 'AVAILABLE'}>
                    {downloading !== r.id && <Download aria-hidden="true" />}
                  </Button>
                  <Button size="icon" variant="outline" className="text-destructive" aria-label={`Delete recording of ${r.meetingTitle}`} onClick={() => setDeleting(r)}>
                    <Trash2 aria-hidden="true" />
                  </Button>
                </div>
              </Card>
            ))}
          </div>
          <Pagination page={q.data.page} totalPages={q.data.totalPages} onPageChange={setPage} />
        </div>
      )}
      </section>
      <Player recording={playing} onClose={() => setPlaying(null)} />
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(o) => !o && setDeleting(null)}
        title="Delete this recording?"
        description="The recording file is permanently removed from storage."
        confirmLabel="Delete"
        onConfirm={async () => {
          if (!deleting) return;
          await recordingsApi.remove(deleting.id);
          toast.success('Recording deleted');
          await qc.invalidateQueries({ queryKey: ['recordings'] });
        }}
      />
    </div>
  );
}
