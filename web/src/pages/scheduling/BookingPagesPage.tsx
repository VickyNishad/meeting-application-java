import { useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { CalendarDays, Copy, ExternalLink, Pencil, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Badge, Card, Input, NativeSelect, Switch, Textarea, Label } from '@/components/ui/primitives';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/overlays';
import { ConfirmDialog, EmptyState, ErrorState, FormField, ListSkeleton, PageHeader, RootError } from '@/components/common';
import { bookingPagesApi } from '@/lib/api/endpoints';
import { ApiError } from '@/lib/api/problem';
import type { BookingPage, BookingPageRequest } from '@/lib/api/types';
import { applyApiErrors, toastError } from '@/lib/forms';
import { appOrigin, copyToClipboard } from '@/lib/utils';
import { numberField } from '@/lib/validation';

const num = (min: number, max: number) => numberField('Enter a number').int().min(min, `Min ${min}`).max(max, `Max ${max}`);

const schema = z.object({
  slug: z.string().regex(/^[a-z0-9-]{3,60}$/, '3–60 lowercase letters, digits or dashes'),
  title: z.string().trim().min(1, 'Title is required').max(200),
  description: z.string().max(5000),
  durationMinutes: num(5, 480),
  slotIntervalMinutes: num(5, 480).or(z.nan()),
  minNoticeMinutes: num(0, 525600),
  maxAdvanceDays: num(1, 730),
  bufferBeforeMinutes: num(0, 480),
  bufferAfterMinutes: num(0, 480),
  mediaMode: z.enum(['VIDEO', 'AUDIO_ONLY']),
  active: z.boolean(),
});
type Values = z.infer<typeof schema>;
const FIELDS = Object.keys(schema.shape);

const bookingLink = (slug: string) => `${appOrigin()}/book/${slug}`;

function slugify(s: string) {
  return s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60);
}

function BookingPageDialog({ page, open, onOpenChange }: { page: BookingPage | null; open: boolean; onOpenChange: (o: boolean) => void }) {
  const qc = useQueryClient();
  // defaultValues (not `values`): a fresh `values` object on every render made RHF reset in a loop
  // ("Maximum update depth exceeded"). The dialog is re-keyed per page, so defaults are enough.
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: page
      ? {
          slug: page.slug,
          title: page.title,
          description: page.description ?? '',
          durationMinutes: page.durationMinutes,
          slotIntervalMinutes: page.slotIntervalMinutes ?? Number.NaN,
          minNoticeMinutes: page.minNoticeMinutes ?? 240,
          maxAdvanceDays: page.maxAdvanceDays ?? 60,
          bufferBeforeMinutes: page.bufferBeforeMinutes ?? 0,
          bufferAfterMinutes: page.bufferAfterMinutes ?? 0,
          mediaMode: page.mediaMode ?? 'VIDEO',
          active: page.active ?? true,
        }
      : {
          slug: '', title: '', description: '', durationMinutes: 30, slotIntervalMinutes: Number.NaN, minNoticeMinutes: 240,
          maxAdvanceDays: 60, bufferBeforeMinutes: 0, bufferAfterMinutes: 0, mediaMode: 'VIDEO', active: true,
        },
  });
  const { register, formState, setValue, watch } = form;
  const e = formState.errors;
  const active = watch('active');

  const onSubmit = form.handleSubmit(async (v) => {
    const body: BookingPageRequest = {
      ...v,
      description: v.description.trim() || undefined,
      slotIntervalMinutes: Number.isFinite(v.slotIntervalMinutes) ? v.slotIntervalMinutes : undefined,
    };
    try {
      if (page) await bookingPagesApi.update(page.id, { ...body, version: page.version });
      else await bookingPagesApi.create(body);
      toast.success(page ? 'Booking page updated' : 'Booking page created');
      await qc.invalidateQueries({ queryKey: ['booking-pages'] });
      onOpenChange(false);
    } catch (err) {
      applyApiErrors(err, form.setError, FIELDS, { toast: false });
    }
  });

  const numField = (name: keyof Values, label: string, hint?: string) => (
    <FormField label={label} error={e[name]?.message} hint={hint}>
      {({ id, describedBy, invalid }) => <Input id={id} type="number" aria-describedby={describedBy} aria-invalid={invalid} {...register(name, { valueAsNumber: true })} />}
    </FormField>
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{page ? 'Edit booking page' : 'New booking page'}</DialogTitle>
          <DialogDescription>Share the public link and let people pick a time that works.</DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} className="grid gap-4 sm:grid-cols-2" noValidate>
          <div className="sm:col-span-2">
            <RootError message={e.root?.server?.message} />
          </div>
          <FormField label="Title" error={e.title?.message}>
            {({ id, describedBy, invalid }) => (
              <Input
                id={id}
                aria-describedby={describedBy}
                aria-invalid={invalid}
                {...register('title', {
                  // Suggest a slug while typing the title, until the user edits the slug themselves.
                  onChange: (ev: React.ChangeEvent<HTMLInputElement>) => {
                    if (!page && !formState.dirtyFields.slug) setValue('slug', slugify(ev.target.value));
                  },
                })}
              />
            )}
          </FormField>
          <FormField label="URL slug" error={e.slug?.message} hint={`${appOrigin()}/book/…`}>
            {({ id, describedBy, invalid }) => <Input id={id} className="font-mono" aria-describedby={describedBy} aria-invalid={invalid} {...register('slug')} />}
          </FormField>
          <FormField label="Description" error={e.description?.message} className="sm:col-span-2">
            {({ id, describedBy, invalid }) => <Textarea id={id} rows={2} aria-describedby={describedBy} aria-invalid={invalid} {...register('description')} />}
          </FormField>
          {numField('durationMinutes', 'Duration (minutes)', '5–480')}
          {numField('slotIntervalMinutes', 'Slot interval (minutes)', 'Empty = same as duration')}
          {numField('minNoticeMinutes', 'Minimum notice (minutes)', 'Default 240')}
          {numField('maxAdvanceDays', 'Bookable up to (days ahead)', 'Default 60')}
          {numField('bufferBeforeMinutes', 'Buffer before (minutes)')}
          {numField('bufferAfterMinutes', 'Buffer after (minutes)')}
          <FormField label="Meeting type" error={e.mediaMode?.message}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} {...register('mediaMode')}>
                <option value="VIDEO">Video call</option>
                <option value="AUDIO_ONLY">Voice call</option>
              </NativeSelect>
            )}
          </FormField>
          <div className="flex items-center gap-3 pt-6">
            <Switch id="bp-active" checked={active} onCheckedChange={(c) => setValue('active', c)} />
            <Label htmlFor="bp-active">{active ? 'Active — accepting bookings' : 'Inactive'}</Label>
          </div>
          <DialogFooter className="sm:col-span-2">
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={formState.isSubmitting}>
              {page ? 'Save' : 'Create'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export default function BookingPagesPage() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['booking-pages'], queryFn: bookingPagesApi.list });
  const [editing, setEditing] = useState<BookingPage | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [deleting, setDeleting] = useState<BookingPage | null>(null);
  const toggle = useMutation({
    mutationFn: (p: BookingPage) => bookingPagesApi.update(p.id, { active: !(p.active ?? true), version: p.version }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['booking-pages'] }),
    onError: (e) => toastError(e),
  });

  const openNew = () => {
    setEditing(null);
    setDialogOpen(true);
  };

  return (
    <div>
      <PageHeader
        title="Booking pages"
        description={
          <>
            Public scheduling links. Slots come from your <Link className="text-primary hover:underline" to="/scheduling/availability">availability</Link>.
          </>
        }
        actions={
          <Button onClick={openNew}>
            <Plus aria-hidden="true" /> New booking page
          </Button>
        }
      />
      {q.isPending ? (
        <ListSkeleton rows={3} />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : q.data.length === 0 ? (
        <EmptyState icon={CalendarDays} title="No booking pages yet" description="Create one and share the link so people can book time with you." action={<Button onClick={openNew}>Create booking page</Button>} />
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {q.data.map((p) => (
            <Card key={p.id} className="flex flex-col gap-3 p-4">
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <h3 className="truncate font-medium">{p.title}</h3>
                  <p className="truncate font-mono text-xs text-muted-foreground">/book/{p.slug}</p>
                </div>
                <Badge variant={(p.active ?? true) ? 'success' : 'secondary'}>{(p.active ?? true) ? 'Active' : 'Inactive'}</Badge>
              </div>
              <p className="text-sm text-muted-foreground">
                {p.durationMinutes} min · {p.mediaMode === 'AUDIO_ONLY' ? 'Voice' : 'Video'}
              </p>
              <div className="mt-auto flex flex-wrap items-center gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => copyToClipboard(bookingLink(p.slug)).then(() => toast.success('Link copied'), toastError)}
                >
                  <Copy aria-hidden="true" /> Copy link
                </Button>
                <Button size="sm" variant="outline" asChild>
                  <a href={`/book/${p.slug}`} target="_blank" rel="noopener noreferrer">
                    <ExternalLink aria-hidden="true" /> Open
                  </a>
                </Button>
                <div className="ml-auto flex items-center gap-1">
                  <Switch checked={p.active ?? true} onCheckedChange={() => toggle.mutate(p)} aria-label={`${(p.active ?? true) ? 'Deactivate' : 'Activate'} ${p.title}`} disabled={toggle.isPending} />
                  <Button size="icon" variant="ghost" aria-label={`Edit ${p.title}`} onClick={() => { setEditing(p); setDialogOpen(true); }}>
                    <Pencil aria-hidden="true" />
                  </Button>
                  <Button size="icon" variant="ghost" className="text-destructive" aria-label={`Delete ${p.title}`} onClick={() => setDeleting(p)}>
                    <Trash2 aria-hidden="true" />
                  </Button>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
      <BookingPageDialog key={`${editing?.id ?? 'new'}-${dialogOpen}`} page={editing} open={dialogOpen} onOpenChange={setDialogOpen} />
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(o) => !o && setDeleting(null)}
        title={`Delete “${deleting?.title ?? ''}”?`}
        description="The public link stops working. A page that still has upcoming bookings can't be deleted."
        confirmLabel="Delete"
        onConfirm={async () => {
          if (!deleting) return;
          try {
            await bookingPagesApi.remove(deleting.id);
          } catch (e) {
            // The dialog shows the server's detail; only fill in a message if it sent none.
            if (e instanceof ApiError && e.code === 'BOOKING_PAGE_HAS_UPCOMING_BOOKINGS' && (!e.detail || e.detail === e.title)) {
              throw new Error('This page still has upcoming bookings. Cancel them, or deactivate the page instead.');
            }
            throw e;
          }
          toast.success('Booking page deleted');
          await qc.invalidateQueries({ queryKey: ['booking-pages'] });
        }}
      />
    </div>
  );
}
