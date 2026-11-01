import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarClock, Globe, Video, XCircle } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Alert, Badge, Card, CardContent, Label } from '@/components/ui/primitives';
import { ConfirmDialog, ErrorState, ListSkeleton, TimeZoneSelect } from '@/components/common';
import { SlotPicker } from '@/components/SlotPicker';
import { bookingsApi } from '@/lib/api/endpoints';
import { ApiError, errorMessage } from '@/lib/api/problem';
import type { Slot } from '@/lib/api/types';
import { formatDateTime, formatTime } from '@/lib/time';

export default function ManageBookingPage() {
  const { token = '' } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['manage-booking', token], queryFn: () => bookingsApi.manage(token) });
  const [cancelOpen, setCancelOpen] = useState(false);
  const [rescheduling, setRescheduling] = useState(false);
  const [tz, setTz] = useState<string | null>(null);
  const [slot, setSlot] = useState<Slot | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (q.isPending) return <div className="mx-auto w-full max-w-2xl py-10"><ListSkeleton rows={3} /></div>;
  if (q.isError) return <div className="mx-auto w-full max-w-md py-10"><ErrorState error={q.error} onRetry={() => void q.refetch()} /></div>;
  const b = q.data;
  const viewTz = tz ?? b.inviteeTimeZone;
  const modifiable = b.status === 'CONFIRMED' && new Date(b.startTime).getTime() > Date.now();

  const confirmReschedule = async () => {
    if (!slot) return;
    setBusy(true);
    setError(null);
    try {
      const res = await bookingsApi.manageReschedule(token, slot.start);
      toast.success('Booking rescheduled');
      void qc.invalidateQueries({ queryKey: ['slots', b.bookingPageSlug] });
      setRescheduling(false);
      setSlot(null);
      if (res.manageToken && res.manageToken !== token) {
        navigate(`/book/${encodeURIComponent(res.bookingPageSlug)}/confirmed`, { state: { confirmation: res, timeZone: viewTz } });
      } else {
        await qc.invalidateQueries({ queryKey: ['manage-booking', token] });
      }
    } catch (e) {
      setError(errorMessage(e));
      if (e instanceof ApiError && e.code === 'SLOT_UNAVAILABLE') {
        setSlot(null);
        await qc.invalidateQueries({ queryKey: ['slots', b.bookingPageSlug] });
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto w-full max-w-2xl py-8">
      <Card>
        <CardContent className="grid gap-5 p-6 sm:p-8">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <div>
              <p className="text-sm text-muted-foreground">Your booking</p>
              <h1 className="text-xl font-semibold">{b.bookingPageTitle}</h1>
            </div>
            <Badge variant={b.status === 'CONFIRMED' ? 'success' : b.status === 'CANCELLED' ? 'destructive' : 'secondary'}>{b.status.toLowerCase()}</Badge>
          </div>
          <div className="rounded-lg border p-4 text-sm">
            <p className="flex items-center gap-2 font-medium">
              <CalendarClock className="size-4" aria-hidden="true" />
              {formatDateTime(b.startTime, viewTz)} – {formatTime(b.endTime, viewTz)}
            </p>
            <p className="mt-1 text-muted-foreground">
              {b.inviteeName} · {b.inviteeEmail}
            </p>
            {b.notes && <p className="mt-2">“{b.notes}”</p>}
          </div>
          <div className="grid max-w-xs gap-1.5">
            <Label htmlFor="mb-tz" className="flex items-center gap-1 text-xs text-muted-foreground">
              <Globe className="size-3.5" aria-hidden="true" /> Show times in
            </Label>
            <TimeZoneSelect id="mb-tz" value={viewTz} onChange={setTz} />
          </div>
          {b.status === 'CONFIRMED' && b.joinUrl && (
            <Button asChild variant="outline" className="justify-self-start">
              <a href={b.joinUrl}>
                <Video aria-hidden="true" /> Join meeting
              </a>
            </Button>
          )}
          {!modifiable && b.status === 'CONFIRMED' && <Alert>This booking has already started and can no longer be changed.</Alert>}
          {modifiable && !rescheduling && (
            <div className="flex flex-wrap gap-2">
              <Button onClick={() => setRescheduling(true)}>
                <CalendarClock aria-hidden="true" /> Reschedule
              </Button>
              <Button variant="outline" className="text-destructive" onClick={() => setCancelOpen(true)}>
                <XCircle aria-hidden="true" /> Cancel booking
              </Button>
            </div>
          )}
          {rescheduling && (
            <div className="grid gap-4 border-t pt-4">
              <h2 className="font-semibold">Pick a new time</h2>
              <SlotPicker slug={b.bookingPageSlug} timeZone={viewTz} days={30} selected={slot?.start ?? null} onSelect={setSlot} exclude={[b.startTime]} />
              {error && <Alert variant="destructive">{error}</Alert>}
              <div className="flex flex-wrap gap-2">
                <Button onClick={() => void confirmReschedule()} disabled={!slot} loading={busy}>
                  {slot ? `Move to ${formatDateTime(slot.start, viewTz)}` : 'Select a time'}
                </Button>
                <Button variant="ghost" onClick={() => { setRescheduling(false); setSlot(null); setError(null); }}>
                  Keep current time
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
      <ConfirmDialog
        open={cancelOpen}
        onOpenChange={setCancelOpen}
        title="Cancel this booking?"
        description="The host will be notified."
        confirmLabel="Cancel booking"
        withReason
        onConfirm={async (reason) => {
          await bookingsApi.manageCancel(token, reason);
          toast.success('Booking cancelled');
          await qc.invalidateQueries({ queryKey: ['manage-booking', token] });
        }}
      />
    </div>
  );
}
