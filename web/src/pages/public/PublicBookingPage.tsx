import { useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { AlertTriangle, ArrowLeft, Clock, Globe, Mic, Video } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Alert, Card, CardContent, Input, Label, Textarea } from '@/components/ui/primitives';
import { ErrorState, FormField, ListSkeleton, RootError, TimeZoneSelect } from '@/components/common';
import { SlotPicker } from '@/components/SlotPicker';
import { bookingPagesApi, bookingsApi } from '@/lib/api/endpoints';
import { ApiError } from '@/lib/api/problem';
import type { Slot } from '@/lib/api/types';
import { applyApiErrors } from '@/lib/forms';
import { browserTimeZone, formatDateTime, formatTime } from '@/lib/time';
import { IdempotencyKeys, withIdempotencyRetry } from '@/lib/idempotency';

const schema = z.object({
  inviteeName: z.string().trim().min(1, 'Name is required').max(200),
  inviteeEmail: z.string().trim().pipe(z.email('Enter a valid email address')),
  notes: z.string().max(2000),
});
type Values = z.infer<typeof schema>;

export default function PublicBookingPage() {
  const { slug = '' } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [timeZone, setTimeZone] = useState(browserTimeZone());
  const [slot, setSlot] = useState<Slot | null>(null);
  // Shown above the slot picker after the chosen time was taken (the form is gone by then).
  const [slotError, setSlotError] = useState<string | null>(null);
  // Same key only for re-sending the identical booking request; another slot or other details → new key.
  const idemKeys = useRef(new IdempotencyKeys());
  const page = useQuery({ queryKey: ['public-booking-page', slug], queryFn: () => bookingPagesApi.publicPage(slug) });
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { inviteeName: '', inviteeEmail: '', notes: '' } });

  const onSubmit = form.handleSubmit(async (v) => {
    if (!slot) return;
    try {
      const body = { slug, startTime: slot.start, inviteeName: v.inviteeName.trim(), inviteeEmail: v.inviteeEmail.trim(), inviteeTimeZone: timeZone, notes: v.notes.trim() || undefined };
      const confirmation = await withIdempotencyRetry(() => bookingsApi.create(body, idemKeys.current.keyFor(body)));
      idemKeys.current.reset();
      void qc.invalidateQueries({ queryKey: ['slots', slug] });
      navigate(`/book/${encodeURIComponent(slug)}/confirmed`, { state: { confirmation, timeZone } });
    } catch (e) {
      applyApiErrors(e, form.setError, ['inviteeName', 'inviteeEmail', 'notes'], { toast: false });
      if (e instanceof ApiError && e.code === 'SLOT_UNAVAILABLE') {
        // Someone else took the slot: refresh availability and let the user pick again.
        setSlotError(e.detail);
        setSlot(null);
        idemKeys.current.reset();
        await qc.invalidateQueries({ queryKey: ['slots', slug] });
      }
    }
  });

  if (page.isPending) return <div className="mx-auto w-full max-w-4xl py-10"><ListSkeleton rows={3} /></div>;
  if (page.isError) return <div className="mx-auto w-full max-w-md py-10"><ErrorState error={page.error} onRetry={() => void page.refetch()} /></div>;
  const p = page.data;
  const e = form.formState.errors;

  return (
    <div className="mx-auto w-full max-w-5xl py-6">
      <Card className="overflow-hidden">
        <div className="grid md:grid-cols-[18rem_1fr]">
          <div className="border-b bg-muted/40 p-6 md:border-b-0 md:border-r">
            <p className="text-sm text-muted-foreground">{p.hostName}</p>
            <h1 className="mt-1 text-xl font-semibold">{p.title}</h1>
            <ul className="mt-4 grid gap-2 text-sm text-muted-foreground">
              <li className="flex items-center gap-2">
                <Clock className="size-4" aria-hidden="true" /> {p.durationMinutes} minutes
              </li>
              <li className="flex items-center gap-2">
                {p.mediaMode === 'AUDIO_ONLY' ? <Mic className="size-4" aria-hidden="true" /> : <Video className="size-4" aria-hidden="true" />}
                {p.mediaMode === 'AUDIO_ONLY' ? 'Voice call' : 'Video call'} — link sent on confirmation
              </li>
            </ul>
            {p.description && <p className="mt-4 whitespace-pre-wrap text-sm">{p.description}</p>}
            {slot && (
              <p className="mt-6 rounded-lg bg-primary/10 p-3 text-sm font-medium text-primary">
                {formatDateTime(slot.start, timeZone)} – {formatTime(slot.end, timeZone)}
              </p>
            )}
          </div>
          <CardContent className="p-6">
            {!slot ? (
              <div className="grid gap-4">
                <div className="flex flex-wrap items-end justify-between gap-3">
                  <h2 className="text-lg font-semibold">Select a date & time</h2>
                  <div className="grid gap-1.5">
                    <Label htmlFor="viewer-tz" className="flex items-center gap-1 text-xs text-muted-foreground">
                      <Globe className="size-3.5" aria-hidden="true" /> Time zone
                    </Label>
                    <TimeZoneSelect id="viewer-tz" value={timeZone} onChange={setTimeZone} className="w-64" />
                  </div>
                </div>
                {slotError && (
                  <Alert variant="destructive">
                    <AlertTriangle aria-hidden="true" />
                    <span>{slotError} Please pick another time.</span>
                  </Alert>
                )}
                <SlotPicker
                  slug={slug}
                  timeZone={timeZone}
                  days={30}
                  selected={null}
                  onSelect={(s) => {
                    setSlotError(null);
                    setSlot(s);
                  }}
                />
              </div>
            ) : (
              <form onSubmit={onSubmit} className="grid gap-4" noValidate>
                <Button type="button" variant="ghost" size="sm" className="justify-self-start" onClick={() => setSlot(null)}>
                  <ArrowLeft aria-hidden="true" /> Pick another time
                </Button>
                <h2 className="text-lg font-semibold">Your details</h2>
                <RootError message={e.root?.server?.message} />
                <FormField label="Name" error={e.inviteeName?.message}>
                  {({ id, describedBy, invalid }) => <Input id={id} autoComplete="name" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('inviteeName')} />}
                </FormField>
                <FormField label="Email" error={e.inviteeEmail?.message}>
                  {({ id, describedBy, invalid }) => <Input id={id} type="email" autoComplete="email" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('inviteeEmail')} />}
                </FormField>
                <FormField label="Anything to share before the meeting? (optional)" error={e.notes?.message}>
                  {({ id, describedBy, invalid }) => <Textarea id={id} rows={3} aria-describedby={describedBy} aria-invalid={invalid} {...form.register('notes')} />}
                </FormField>
                <p className="text-xs text-muted-foreground">Times are shown in {timeZone}.</p>
                <Button type="submit" loading={form.formState.isSubmitting}>
                  Confirm booking
                </Button>
              </form>
            )}
          </CardContent>
        </div>
      </Card>
    </div>
  );
}
