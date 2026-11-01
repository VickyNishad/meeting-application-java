import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarCheck, Mail, Video } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Badge, Card } from '@/components/ui/primitives';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/overlays';
import { ConfirmDialog, EmptyState, ErrorState, ListSkeleton, PageHeader, Pagination } from '@/components/common';
import { bookingsApi } from '@/lib/api/endpoints';
import type { Booking } from '@/lib/api/types';
import { browserTimeZone, formatDate, formatTime } from '@/lib/time';

function BookingList({ scope }: { scope: 'upcoming' | 'past' }) {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [cancelling, setCancelling] = useState<Booking | null>(null);
  const tz = browserTimeZone();
  const q = useQuery({ queryKey: ['bookings', scope, page], queryFn: () => bookingsApi.list(scope, page, 20), placeholderData: keepPreviousData });

  if (q.isPending) return <ListSkeleton rows={3} />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (q.data.items.length === 0) {
    return (
      <EmptyState
        icon={CalendarCheck}
        title={scope === 'upcoming' ? 'No upcoming bookings' : 'No past bookings'}
        description="Share a booking page to start getting bookings."
        action={
          <Button asChild variant="outline">
            <Link to="/scheduling/pages">Booking pages</Link>
          </Button>
        }
      />
    );
  }
  return (
    <>
      <div className="grid gap-3">
        {q.data.items.map((b) => (
          <Card key={b.id} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
            <div className="w-28 shrink-0">
              <p className="text-sm font-semibold">{formatDate(b.startTime, tz)}</p>
              <p className="text-xs text-muted-foreground">
                {formatTime(b.startTime, tz)} – {formatTime(b.endTime, tz)}
              </p>
            </div>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{b.inviteeName}</span>
                <Badge variant={b.status === 'CONFIRMED' ? 'success' : b.status === 'CANCELLED' ? 'destructive' : 'secondary'}>{b.status.toLowerCase()}</Badge>
              </div>
              <p className="mt-0.5 flex flex-wrap gap-x-3 text-sm text-muted-foreground">
                <span>{b.bookingPageTitle}</span>
                <a className="inline-flex items-center gap-1 hover:underline" href={`mailto:${b.inviteeEmail}`}>
                  <Mail className="size-3.5" aria-hidden="true" /> {b.inviteeEmail}
                </a>
                <span>Invitee TZ: {b.inviteeTimeZone}</span>
              </p>
              {b.notes && <p className="mt-1 text-sm">“{b.notes}”</p>}
            </div>
            <div className="flex gap-2">
              {b.meetingId && b.status === 'CONFIRMED' && scope === 'upcoming' && (
                <Button size="sm" variant="outline" asChild>
                  <Link to={`/meetings/${b.meetingId}`}>
                    <Video aria-hidden="true" /> Meeting
                  </Link>
                </Button>
              )}
              {b.status === 'CONFIRMED' && scope === 'upcoming' && (
                <Button size="sm" variant="outline" className="text-destructive" onClick={() => setCancelling(b)}>
                  Cancel
                </Button>
              )}
            </div>
          </Card>
        ))}
      </div>
      <Pagination page={q.data.page} totalPages={q.data.totalPages} onPageChange={setPage} />
      <ConfirmDialog
        open={cancelling !== null}
        onOpenChange={(o) => !o && setCancelling(null)}
        title={`Cancel the booking with ${cancelling?.inviteeName ?? ''}?`}
        description="The invitee is notified by email."
        confirmLabel="Cancel booking"
        withReason
        onConfirm={async (reason) => {
          if (!cancelling) return;
          await bookingsApi.cancel(cancelling.id, reason);
          toast.success('Booking cancelled');
          await qc.invalidateQueries({ queryKey: ['bookings'] });
        }}
      />
    </>
  );
}

export default function HostBookingsPage() {
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'past' ? 'past' : 'upcoming';
  return (
    <div>
      <PageHeader title="Bookings" description={`Times shown in your time zone (${browserTimeZone()}).`} />
      <Tabs value={tab} onValueChange={(v) => setParams({ tab: v }, { replace: true })}>
        <TabsList>
          <TabsTrigger value="upcoming">Upcoming</TabsTrigger>
          <TabsTrigger value="past">Past</TabsTrigger>
        </TabsList>
        <TabsContent value="upcoming">
          <BookingList scope="upcoming" />
        </TabsContent>
        <TabsContent value="past">
          <BookingList scope="past" />
        </TabsContent>
      </Tabs>
    </div>
  );
}
