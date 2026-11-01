import { useMemo, useRef } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { Controller, useForm, type Control } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle, Input, NativeSelect, Switch, Textarea, Label } from '@/components/ui/primitives';
import { ErrorState, FormField, ListSkeleton, PageHeader, RootError, TimeZoneSelect } from '@/components/common';
import { meetingsApi, orgsApi } from '@/lib/api/endpoints';
import type { CreateMeetingRequest, Meeting, UpdateMeetingRequest } from '@/lib/api/types';
import { addDaysToKey, browserTimeZone, dateKeyInZone, utcToZonedLocal, zonedLocalToUtc } from '@/lib/time';
import { applyApiErrors } from '@/lib/forms';
import { IdempotencyKeys, withIdempotencyRetry } from '@/lib/idempotency';
import { emailListSchema, numberField } from '@/lib/validation';

const RECURRENCE_PRESETS = {
  NONE: { label: 'Does not repeat', rule: '' },
  DAILY: { label: 'Daily', rule: 'FREQ=DAILY' },
  WEEKDAYS: { label: 'Every weekday (Mon–Fri)', rule: 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR' },
  WEEKLY: { label: 'Weekly', rule: 'FREQ=WEEKLY' },
  BIWEEKLY: { label: 'Every 2 weeks', rule: 'FREQ=WEEKLY;INTERVAL=2' },
  MONTHLY: { label: 'Monthly', rule: 'FREQ=MONTHLY' },
  CUSTOM: { label: 'Custom RRULE…', rule: '' },
} as const;
type Preset = keyof typeof RECURRENCE_PRESETS;

const schema = z
  .object({
    title: z.string().trim().min(1, 'Title is required').max(200),
    description: z.string().max(5000).optional(),
    // PERSONAL_ROOM only ever comes from an existing meeting (edit); it has no schedule.
    type: z.enum(['INSTANT', 'SCHEDULED', 'ONE_TO_ONE', 'PERSONAL_ROOM']),
    mediaMode: z.enum(['VIDEO', 'AUDIO_ONLY']),
    privacy: z.enum(['LINK', 'ORGANIZATION', 'INVITEES_ONLY']),
    organizationId: z.string(),
    date: z.string(),
    time: z.string(),
    durationMinutes: numberField('Enter a duration').int().min(5, 'At least 5 minutes').max(1440, 'At most 24 hours'),
    timeZone: z.string().min(1),
    recurrencePreset: z.enum(Object.keys(RECURRENCE_PRESETS) as [Preset, ...Preset[]]),
    recurrenceCount: z.number().int().min(1).max(365).or(z.nan()),
    customRrule: z.string(),
    password: z.string().refine((v) => v === '' || (v.length >= 4 && v.length <= 64), 'Use 4–64 characters'),
    removePassword: z.boolean(),
    waitingRoomEnabled: z.boolean(),
    allowGuests: z.boolean(),
    joinBeforeHost: z.boolean(),
    muteOnEntry: z.boolean(),
    autoRecord: z.boolean(),
    maxParticipants: z.number().int().min(2, 'At least 2').max(300, 'At most 300').or(z.nan()),
    screenSharePolicy: z.enum(['EVERYONE', 'HOSTS_ONLY']),
    visibilityMode: z.enum(['EVERYONE_VISIBLE', 'HOST_AND_SELF_ONLY']),
    inviteeEmails: z.string(),
  })
  .superRefine((v, ctx) => {
    if (hasSchedule(v.type)) {
      if (!v.date) ctx.addIssue({ code: 'custom', path: ['date'], message: 'Pick a date' });
      if (!v.time) ctx.addIssue({ code: 'custom', path: ['time'], message: 'Pick a time' });
    }
    if (v.recurrencePreset === 'CUSTOM' && !/^FREQ=/i.test(v.customRrule.trim().replace(/^RRULE:/i, ''))) {
      ctx.addIssue({ code: 'custom', path: ['customRrule'], message: 'Enter an RFC 5545 rule, e.g. FREQ=WEEKLY;BYDAY=MO' });
    }
    const emails = emailListSchema.safeParse(v.inviteeEmails);
    if (!emails.success) {
      ctx.addIssue({ code: 'custom', path: ['inviteeEmails'], message: emails.error.issues[0]?.message ?? 'Invalid emails' });
    }
  });
type Values = z.infer<typeof schema>;

/** Instant meetings start now and personal rooms are always available: neither has a date, time or recurrence. */
function hasSchedule(type: Values['type']): boolean {
  return type !== 'INSTANT' && type !== 'PERSONAL_ROOM';
}

type MeetingFields = Omit<CreateMeetingRequest, 'inviteeEmails' | 'type'> & { type: Values['type'] };

/** Request fields as the API expects them. Personal rooms never carry schedule fields (the backend rejects them). */
function toRequestFields(v: Values): MeetingFields {
  const startTime = !hasSchedule(v.type) || !v.date || !v.time ? undefined : zonedLocalToUtc(v.date, v.time, v.timeZone).toISOString();
  const personal = v.type === 'PERSONAL_ROOM';
  return {
    title: v.title.trim(),
    description: v.description?.trim() || undefined,
    type: v.type,
    mediaMode: v.mediaMode,
    privacy: v.privacy,
    organizationId: v.organizationId || undefined,
    startTime,
    durationMinutes: personal ? undefined : v.durationMinutes,
    timeZone: v.timeZone,
    recurrenceRule: buildRrule(v),
    password: v.password || undefined,
    waitingRoomEnabled: v.waitingRoomEnabled,
    allowGuests: v.allowGuests,
    joinBeforeHost: v.joinBeforeHost,
    muteOnEntry: v.muteOnEntry,
    autoRecord: v.autoRecord,
    maxParticipants: v.type === 'ONE_TO_ONE' ? 2 : Number.isFinite(v.maxParticipants) ? v.maxParticipants : undefined,
    screenSharePolicy: v.screenSharePolicy,
    visibilityMode: v.visibilityMode,
  };
}

/**
 * PATCH body with only the fields that differ from the meeting as loaded, so e.g. renaming a LIVE meeting or a
 * recurring series whose first occurrence is in the past doesn't re-send (and get rejected on) its start time.
 */
function buildUpdateBody(meeting: Meeting, v: Values): UpdateMeetingRequest {
  const before = toRequestFields(defaultsFor(meeting));
  const after = toRequestFields(v);
  const body: UpdateMeetingRequest = { version: meeting.version };
  const changed = body as Record<string, unknown>;
  for (const k of Object.keys(after) as (keyof MeetingFields)[]) {
    // The meeting type can't change after creation, so it is never sent.
    if (k === 'type') continue;
    if (JSON.stringify(after[k]) !== JSON.stringify(before[k])) changed[k] = after[k];
  }
  if (meeting.type === 'PERSONAL_ROOM') {
    delete changed.startTime;
    delete changed.durationMinutes;
    delete changed.recurrenceRule;
  }
  // PATCH semantics: omitted password = keep; empty string = remove protection.
  if (v.removePassword) body.password = '';
  return body;
}

const FIELD_NAMES = [
  'title', 'description', 'type', 'mediaMode', 'privacy', 'organizationId', 'durationMinutes', 'timeZone',
  'password', 'waitingRoomEnabled', 'allowGuests', 'joinBeforeHost', 'muteOnEntry', 'autoRecord', 'maxParticipants',
  'screenSharePolicy', 'visibilityMode', 'inviteeEmails', 'customRrule', 'date',
] as const;

function buildRrule(v: Values): string | undefined {
  if (!hasSchedule(v.type) || v.recurrencePreset === 'NONE') return undefined;
  if (v.recurrencePreset === 'CUSTOM') return v.customRrule.trim().replace(/^RRULE:/i, '') || undefined;
  const base = RECURRENCE_PRESETS[v.recurrencePreset].rule;
  return Number.isFinite(v.recurrenceCount) ? `${base};COUNT=${v.recurrenceCount}` : base;
}

function defaultsFor(meeting: Meeting | undefined): Values {
  const tz = meeting?.timeZone ?? browserTimeZone();
  if (!meeting) {
    // Next full hour in the browser zone.
    const next = new Date(Math.ceil((Date.now() + 15 * 60_000) / 3_600_000) * 3_600_000);
    const local = utcToZonedLocal(next.toISOString(), tz);
    return {
      title: '', description: '', type: 'SCHEDULED', mediaMode: 'VIDEO', privacy: 'LINK', organizationId: '',
      date: local.date, time: local.time, durationMinutes: 60, timeZone: tz, recurrencePreset: 'NONE',
      recurrenceCount: Number.NaN, customRrule: '', password: '', removePassword: false, waitingRoomEnabled: false, allowGuests: true,
      joinBeforeHost: false, muteOnEntry: false, autoRecord: false, maxParticipants: 100, screenSharePolicy: 'EVERYONE',
      visibilityMode: 'EVERYONE_VISIBLE', inviteeEmails: '',
    };
  }
  const local = meeting.startTime ? utcToZonedLocal(meeting.startTime, tz) : { date: '', time: '' };
  const duration =
    meeting.startTime && meeting.endTime
      ? Math.round((new Date(meeting.endTime).getTime() - new Date(meeting.startTime).getTime()) / 60000)
      : 60;
  return {
    title: meeting.title,
    description: meeting.description ?? '',
    type: meeting.type,
    mediaMode: meeting.mediaMode,
    privacy: meeting.privacy,
    organizationId: meeting.organizationId ?? '',
    date: local.date,
    time: local.time,
    durationMinutes: duration,
    timeZone: tz,
    recurrencePreset: meeting.recurrenceRule ? 'CUSTOM' : 'NONE',
    recurrenceCount: Number.NaN,
    customRrule: meeting.recurrenceRule ?? '',
    password: '',
    removePassword: false,
    waitingRoomEnabled: meeting.waitingRoomEnabled,
    allowGuests: meeting.allowGuests,
    joinBeforeHost: meeting.joinBeforeHost,
    muteOnEntry: meeting.muteOnEntry,
    autoRecord: meeting.autoRecord,
    maxParticipants: meeting.maxParticipants,
    screenSharePolicy: meeting.screenSharePolicy,
    visibilityMode: meeting.visibilityMode ?? 'EVERYONE_VISIBLE',
    inviteeEmails: '',
  };
}

function SwitchField({ control, name, label, hint }: { control: Control<Values>; name: 'waitingRoomEnabled' | 'allowGuests' | 'joinBeforeHost' | 'muteOnEntry' | 'autoRecord'; label: string; hint: string }) {
  const id = `sw-${name}`;
  return (
    <Controller
      control={control}
      name={name}
      render={({ field }) => (
        <div className="flex items-start justify-between gap-4 rounded-lg border p-3">
          <div>
            <Label htmlFor={id}>{label}</Label>
            <p className="mt-1 text-xs text-muted-foreground">{hint}</p>
          </div>
          <Switch id={id} checked={field.value} onCheckedChange={field.onChange} />
        </div>
      )}
    />
  );
}

function MeetingForm({ meeting }: { meeting?: Meeting }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const isEdit = !!meeting;
  // Same key only for re-sending the identical create request (e.g. after a lost response).
  const idemKeys = useRef(new IdempotencyKeys());
  const orgs = useQuery({ queryKey: ['organizations'], queryFn: orgsApi.list });

  const defaults = useMemo(() => defaultsFor(meeting), [meeting]);
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: defaults });
  const { register, control, watch, formState } = form;
  const errors = formState.errors;
  const type = watch('type');
  const preset = watch('recurrencePreset');
  const removePassword = watch('removePassword');
  const tz = watch('timeZone');
  const minDate = dateKeyInZone(new Date(), tz || browserTimeZone());

  const onSubmit = form.handleSubmit(async (v) => {
    try {
      let saved: Meeting;
      if (meeting) {
        saved = await meetingsApi.update(meeting.id, buildUpdateBody(meeting, v));
        toast.success('Meeting updated');
      } else {
        const emails = emailListSchema.parse(v.inviteeEmails);
        // The type select never offers PERSONAL_ROOM for new meetings.
        const body = { ...toRequestFields(v), inviteeEmails: emails.length ? emails : undefined } as CreateMeetingRequest;
        saved = await withIdempotencyRetry(() => meetingsApi.create(body, idemKeys.current.keyFor(body)));
        idemKeys.current.reset();
        toast.success(v.type === 'INSTANT' ? 'Meeting created' : 'Meeting scheduled');
      }
      await qc.invalidateQueries({ queryKey: ['meetings'] });
      qc.setQueryData(['meeting', saved.id], saved);
      navigate(v.type === 'INSTANT' && !isEdit ? `/meetings/${saved.id}/room` : `/meetings/${saved.id}`);
    } catch (e) {
      applyApiErrors(e, form.setError, FIELD_NAMES, { aliases: { startTime: 'date', recurrenceRule: 'customRrule' } });
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="grid gap-6">
      <RootError message={errors.root?.server?.message} />
      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2">
          <FormField label="Title" error={errors.title?.message} className="sm:col-span-2">
            {({ id, describedBy, invalid }) => <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} maxLength={200} {...register('title')} />}
          </FormField>
          <FormField label="Description" error={errors.description?.message} className="sm:col-span-2">
            {({ id, describedBy, invalid }) => <Textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} maxLength={5000} {...register('description')} />}
          </FormField>
          <FormField label="Meeting type" error={errors.type?.message}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} {...register('type')} disabled={isEdit}>
                <option value="SCHEDULED">Scheduled</option>
                <option value="INSTANT">Instant (start now)</option>
                <option value="ONE_TO_ONE">One-to-one call</option>
                {type === 'PERSONAL_ROOM' && <option value="PERSONAL_ROOM">Personal room</option>}
              </NativeSelect>
            )}
          </FormField>
          <FormField label="Media" error={errors.mediaMode?.message}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} {...register('mediaMode')}>
                <option value="VIDEO">Video call</option>
                <option value="AUDIO_ONLY">Voice only</option>
              </NativeSelect>
            )}
          </FormField>
          <FormField label="Who can join" error={errors.privacy?.message}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} {...register('privacy')}>
                <option value="LINK">Anyone with the link</option>
                <option value="ORGANIZATION">Organization members</option>
                <option value="INVITEES_ONLY">Invitees only</option>
              </NativeSelect>
            )}
          </FormField>
          <FormField label="Organization" error={errors.organizationId?.message} hint={orgs.isError ? 'Could not load organizations.' : undefined}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} {...register('organizationId')}>
                <option value="">None (personal)</option>
                {orgs.data?.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </NativeSelect>
            )}
          </FormField>
        </CardContent>
      </Card>

      {type !== 'PERSONAL_ROOM' && (
        <Card>
          <CardHeader>
            <CardTitle>When</CardTitle>
            <CardDescription>Times are interpreted in the selected time zone (DST-aware).</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4 sm:grid-cols-2">
            {hasSchedule(type) && (
              <>
                <FormField label="Date" error={errors.date?.message}>
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} type="date" min={minDate} max={addDaysToKey(minDate, 730)} aria-describedby={describedBy} aria-invalid={invalid} {...register('date')} />
                  )}
                </FormField>
                <FormField label="Start time" error={errors.time?.message}>
                  {({ id, describedBy, invalid }) => <Input id={id} type="time" step={300} aria-describedby={describedBy} aria-invalid={invalid} {...register('time')} />}
                </FormField>
              </>
            )}
            <FormField label="Duration (minutes)" error={errors.durationMinutes?.message}>
              {({ id, describedBy, invalid }) => (
                <Input id={id} type="number" min={5} max={1440} step={5} aria-describedby={describedBy} aria-invalid={invalid} {...register('durationMinutes', { valueAsNumber: true })} />
              )}
            </FormField>
            <FormField label="Time zone" error={errors.timeZone?.message}>
              {({ id, describedBy, invalid }) => (
                <Controller control={control} name="timeZone" render={({ field }) => <TimeZoneSelect id={id} value={field.value} onChange={field.onChange} aria-describedby={describedBy} aria-invalid={invalid} />} />
              )}
            </FormField>
            {hasSchedule(type) && (
              <>
                <FormField label="Repeats" error={errors.recurrencePreset?.message}>
                  {({ id, describedBy }) => (
                    <NativeSelect id={id} aria-describedby={describedBy} {...register('recurrencePreset')}>
                      {(Object.keys(RECURRENCE_PRESETS) as Preset[]).map((k) => (
                        <option key={k} value={k}>
                          {RECURRENCE_PRESETS[k].label}
                        </option>
                      ))}
                    </NativeSelect>
                  )}
                </FormField>
                {preset !== 'NONE' && preset !== 'CUSTOM' && (
                  <FormField label="Number of occurrences" error={errors.recurrenceCount?.message} hint="Leave empty to repeat indefinitely.">
                    {({ id, describedBy, invalid }) => (
                      <Input id={id} type="number" min={1} max={365} aria-describedby={describedBy} aria-invalid={invalid} {...register('recurrenceCount', { valueAsNumber: true })} />
                    )}
                  </FormField>
                )}
                {preset === 'CUSTOM' && (
                  <FormField label="RRULE" error={errors.customRrule?.message} hint="RFC 5545, e.g. FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10">
                    {({ id, describedBy, invalid }) => <Input id={id} className="font-mono" aria-describedby={describedBy} aria-invalid={invalid} {...register('customRrule')} />}
                  </FormField>
                )}
              </>
            )}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Security & participants</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2">
          <FormField
            label={isEdit && meeting?.passwordProtected ? 'New password' : 'Password (optional)'}
            error={errors.password?.message}
            hint={isEdit && meeting?.passwordProtected ? 'Leave empty to keep the current password.' : '4–64 characters.'}
          >
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="password" autoComplete="new-password" disabled={removePassword} aria-describedby={describedBy} aria-invalid={invalid} {...register('password')} />
            )}
          </FormField>
          {isEdit && meeting?.passwordProtected && (
            <div className="flex items-center gap-2 sm:col-span-2">
              <input
                id="remove-password"
                type="checkbox"
                className="size-4 rounded border-input accent-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                {...register('removePassword', { onChange: (e: React.ChangeEvent<HTMLInputElement>) => e.target.checked && form.setValue('password', '') })}
              />
              <Label htmlFor="remove-password">Remove password protection</Label>
            </div>
          )}
          <FormField label="Max participants" error={errors.maxParticipants?.message} hint={type === 'ONE_TO_ONE' ? 'One-to-one calls are limited to 2.' : '2–300'}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="number" min={2} max={300} disabled={type === 'ONE_TO_ONE'} aria-describedby={describedBy} aria-invalid={invalid} {...register('maxParticipants', { valueAsNumber: true })} />
            )}
          </FormField>
          <FormField label="Screen sharing" error={errors.screenSharePolicy?.message}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} {...register('screenSharePolicy')}>
                <option value="EVERYONE">Everyone can share</option>
                <option value="HOSTS_ONLY">Hosts and co-hosts only</option>
              </NativeSelect>
            )}
          </FormField>
          <FormField
            label="Who can see whom"
            error={errors.visibilityMode?.message}
            hint={
              isEdit && meeting?.status === 'LIVE'
                ? 'Cannot be changed while the meeting is live.'
                : 'Host and self only: attendees see and hear only the host, co-hosts and themselves; the media server keeps them apart and their names stay hidden from each other.'
            }
          >
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} disabled={isEdit && meeting?.status === 'LIVE'} {...register('visibilityMode')}>
                <option value="EVERYONE_VISIBLE">Everyone visible</option>
                <option value="HOST_AND_SELF_ONLY">Host and self only</option>
              </NativeSelect>
            )}
          </FormField>
          <SwitchField control={control} name="waitingRoomEnabled" label="Waiting room" hint="Hosts admit participants manually." />
          <SwitchField control={control} name="allowGuests" label="Allow guests" hint="People without an account can join with a name." />
          <SwitchField control={control} name="joinBeforeHost" label="Join before host" hint="Participants can enter before the host arrives." />
          <SwitchField control={control} name="muteOnEntry" label="Mute on entry" hint="Microphones start muted." />
          <SwitchField
            control={control}
            name="autoRecord"
            label="Record automatically"
            hint="Records the meeting as one video that follows whoever is speaking (in a webinar, only the host and co-hosts), and a clip of each person every time they speak. Everyone is told before joining. The recordings are yours."
          />
        </CardContent>
      </Card>

      {!isEdit && (
        <Card>
          <CardHeader>
            <CardTitle>Invitations</CardTitle>
            <CardDescription>Invitees receive an email with the join link and a calendar invitation.</CardDescription>
          </CardHeader>
          <CardContent>
            <FormField label="Invitee emails" error={errors.inviteeEmails?.message} hint="Separate addresses with commas, spaces or new lines (max 300).">
              {({ id, describedBy, invalid }) => <Textarea id={id} rows={3} placeholder="ana@example.com, li@example.com" aria-describedby={describedBy} aria-invalid={invalid} {...register('inviteeEmails')} />}
            </FormField>
          </CardContent>
        </Card>
      )}

      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={() => navigate(-1)}>
          Cancel
        </Button>
        <Button type="submit" loading={formState.isSubmitting}>
          {isEdit ? 'Save changes' : type === 'INSTANT' ? 'Create & start' : 'Schedule meeting'}
        </Button>
      </div>
    </form>
  );
}

export default function MeetingFormPage() {
  const { meetingId } = useParams();
  const q = useQuery({ queryKey: ['meeting', meetingId], queryFn: () => meetingsApi.get(meetingId ?? ''), enabled: !!meetingId });

  if (!meetingId) {
    return (
      <div className="mx-auto max-w-3xl">
        <PageHeader title="New meeting" description="Schedule a meeting or start one instantly." />
        <MeetingForm />
      </div>
    );
  }
  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title="Edit meeting" description={<Link to={`/meetings/${meetingId}`} className="text-primary hover:underline">Back to details</Link>} />
      {q.isPending ? (
        <ListSkeleton rows={4} />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : q.data.status === 'ENDED' || q.data.status === 'CANCELLED' ? (
        <ErrorState error={new Error('Ended or cancelled meetings cannot be edited.')} />
      ) : (
        <MeetingForm key={q.data.version} meeting={q.data} />
      )}
    </div>
  );
}
