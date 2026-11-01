import { Link, useNavigate } from 'react-router';
import { useMutation } from '@tanstack/react-query';
import { CalendarClock, Copy, Lock, Mic, Repeat, Users, Video } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from './ui/button';
import { Badge, Card } from './ui/primitives';
import { MeetingStatusBadge } from './common';
import { meetingsApi } from '@/lib/api/endpoints';
import type { Meeting } from '@/lib/api/types';
import { formatDateTime } from '@/lib/time';
import { browserTimeZone } from '@/lib/time';
import { copyToClipboard, joinLinkFor } from '@/lib/utils';
import { toastError } from '@/lib/forms';

export const MEETING_TYPE_LABEL: Record<Meeting['type'], string> = {
  INSTANT: 'Instant',
  SCHEDULED: 'Scheduled',
  PERSONAL_ROOM: 'Personal room',
  ONE_TO_ONE: 'One-to-one',
};

export async function copyJoinLink(m: Pick<Meeting, 'joinCode' | 'joinUrl'>): Promise<void> {
  try {
    await copyToClipboard(joinLinkFor(m.joinCode, m.joinUrl));
    toast.success('Join link copied');
  } catch (e) {
    toastError(e);
  }
}

export function canStartOrJoin(m: Meeting): boolean {
  return m.status === 'LIVE' || m.status === 'SCHEDULED';
}

export function MeetingCard({ meeting }: { meeting: Meeting }) {
  const navigate = useNavigate();
  const tz = browserTimeZone();
  const isHost = meeting.myRole === 'HOST' || meeting.myRole === 'CO_HOST';
  return (
    <Card className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
      <div
        className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
        aria-hidden="true"
      >
        {meeting.mediaMode === 'AUDIO_ONLY' ? <Mic className="size-5" /> : <Video className="size-5" />}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <Link
            to={`/meetings/${meeting.id}`}
            className="truncate font-medium hover:underline focus-visible:underline focus-visible:outline-none"
          >
            {meeting.title}
          </Link>
          <MeetingStatusBadge status={meeting.status} />
          {meeting.passwordProtected && (
            <Badge variant="secondary">
              <Lock className="size-3" aria-hidden="true" /> Password
            </Badge>
          )}
          {meeting.recurrenceRule && (
            <Badge variant="secondary">
              <Repeat className="size-3" aria-hidden="true" /> Recurring
            </Badge>
          )}
        </div>
        <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
          <span className="inline-flex items-center gap-1">
            <CalendarClock className="size-3.5" aria-hidden="true" />
            {meeting.startTime ? formatDateTime(meeting.startTime, tz) : MEETING_TYPE_LABEL[meeting.type]}
          </span>
          <span className="inline-flex items-center gap-1">
            <Users className="size-3.5" aria-hidden="true" /> Host: {meeting.host.displayName}
          </span>
        </p>
      </div>
      <div className="flex gap-2">
        <Button variant="ghost" size="icon" aria-label={`Copy join link for ${meeting.title}`} onClick={() => void copyJoinLink(meeting)}>
          <Copy aria-hidden="true" />
        </Button>
        {canStartOrJoin(meeting) && (
          <Button size="sm" onClick={() => navigate(`/meetings/${meeting.id}/room`)}>
            {meeting.status === 'LIVE' ? 'Join' : isHost ? 'Start' : 'Join'}
          </Button>
        )}
      </div>
    </Card>
  );
}

/** Creates an INSTANT meeting and opens the room. */
export function useInstantMeeting() {
  const navigate = useNavigate();
  return useMutation({
    mutationFn: () =>
      meetingsApi.create({
        title: `Instant meeting — ${new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date())}`,
        type: 'INSTANT',
        timeZone: browserTimeZone(),
      }),
    onSuccess: (m) => navigate(`/meetings/${m.id}/room`),
    onError: (e) => toastError(e, 'Could not start meeting'),
  });
}
