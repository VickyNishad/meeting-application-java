import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarOff, Copy, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Input, Label, Switch } from '@/components/ui/primitives';
import { ConfirmDialog, ErrorState, ListSkeleton, PageHeader, TimeZoneSelect } from '@/components/common';
import { availabilityApi } from '@/lib/api/endpoints';
import { ApiError, errorMessage } from '@/lib/api/problem';
import type { Availability, AvailabilityException, DayOfWeek, WeeklyRule } from '@/lib/api/types';
import { DAYS_OF_WEEK, addDaysToKey, browserTimeZone, dateKeyInZone, formatDateKey } from '@/lib/time';

type Interval = { startTime: string; endTime: string };
type Week = Record<DayOfWeek, Interval[]>;

const DAY_LABEL: Record<DayOfWeek, string> = {
  MONDAY: 'Monday', TUESDAY: 'Tuesday', WEDNESDAY: 'Wednesday', THURSDAY: 'Thursday', FRIDAY: 'Friday', SATURDAY: 'Saturday', SUNDAY: 'Sunday',
};

function toWeek(rules: WeeklyRule[]): Week {
  const w = Object.fromEntries(DAYS_OF_WEEK.map((d) => [d, [] as Interval[]])) as Week;
  for (const r of rules) w[r.dayOfWeek]?.push({ startTime: r.startTime.slice(0, 5), endTime: r.endTime.slice(0, 5) });
  for (const d of DAYS_OF_WEEK) w[d].sort((a, b) => a.startTime.localeCompare(b.startTime));
  return w;
}

/** Client-side mirror of the server rules: start < end, no overlaps on the same day. */
function validateWeek(w: Week): Partial<Record<DayOfWeek, string>> {
  const errors: Partial<Record<DayOfWeek, string>> = {};
  for (const d of DAYS_OF_WEEK) {
    const list = [...w[d]].sort((a, b) => a.startTime.localeCompare(b.startTime));
    for (let i = 0; i < list.length; i++) {
      const cur = list[i]!;
      if (!cur.startTime || !cur.endTime || cur.startTime >= cur.endTime) {
        errors[d] = 'Each interval must end after it starts.';
        break;
      }
      const next = list[i + 1];
      if (next && next.startTime < cur.endTime) {
        errors[d] = 'Intervals overlap.';
        break;
      }
    }
  }
  return errors;
}

function WeeklyEditor({ availability }: { availability: Availability }) {
  const qc = useQueryClient();
  const [timeZone, setTimeZone] = useState(availability.timeZone || browserTimeZone());
  const [week, setWeek] = useState<Week>(() => toWeek(availability.weeklyRules));
  const [errors, setErrors] = useState<Partial<Record<DayOfWeek, string>>>({});
  const [serverError, setServerError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: () =>
      availabilityApi.put({
        timeZone,
        weeklyRules: DAYS_OF_WEEK.flatMap((d) => week[d].map((i) => ({ dayOfWeek: d, startTime: i.startTime, endTime: i.endTime }))),
      }),
    onSuccess: (a) => {
      qc.setQueryData(['availability'], a);
      toast.success('Availability saved');
    },
    onError: (e) => {
      setServerError(errorMessage(e));
      if (e instanceof ApiError && e.fieldErrors.length) setServerError([e.detail, ...e.fieldErrors.map((f) => `${f.field}: ${f.message}`)].join(' — '));
    },
  });

  const update = (d: DayOfWeek, list: Interval[]) => setWeek((w) => ({ ...w, [d]: list }));

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    setServerError(null);
    const errs = validateWeek(week);
    setErrors(errs);
    if (Object.keys(errs).length === 0) save.mutate();
  };

  const copyToWeekdays = (from: DayOfWeek) => {
    setWeek((w) => {
      const next = { ...w };
      for (const d of ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'] as DayOfWeek[]) next[d] = w[from].map((i) => ({ ...i }));
      return next;
    });
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Weekly hours</CardTitle>
        <CardDescription>When people can book time with you. Times are in the selected time zone and DST-aware.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} className="grid gap-4" noValidate>
          <div className="grid max-w-sm gap-1.5">
            <Label htmlFor="av-tz">Time zone</Label>
            <TimeZoneSelect id="av-tz" value={timeZone} onChange={setTimeZone} />
          </div>
          <div className="divide-y rounded-lg border">
            {DAYS_OF_WEEK.map((d) => {
              const list = week[d];
              const enabled = list.length > 0;
              return (
                <div key={d} className="flex flex-col gap-3 p-3 sm:flex-row sm:items-start">
                  <div className="flex w-40 shrink-0 items-center gap-3 pt-1.5">
                    <Switch
                      id={`day-${d}`}
                      checked={enabled}
                      onCheckedChange={(c) => update(d, c ? [{ startTime: '09:00', endTime: '17:00' }] : [])}
                    />
                    <Label htmlFor={`day-${d}`}>{DAY_LABEL[d]}</Label>
                  </div>
                  <div className="grid flex-1 gap-2">
                    {!enabled && <p className="pt-1.5 text-sm text-muted-foreground">Unavailable</p>}
                    {list.map((iv, idx) => (
                      <div key={idx} className="flex flex-wrap items-center gap-2">
                        <Input
                          type="time"
                          aria-label={`${DAY_LABEL[d]} interval ${idx + 1} start`}
                          className="w-32"
                          value={iv.startTime}
                          onChange={(e) => update(d, list.map((x, i) => (i === idx ? { ...x, startTime: e.target.value } : x)))}
                        />
                        <span className="text-muted-foreground">–</span>
                        <Input
                          type="time"
                          aria-label={`${DAY_LABEL[d]} interval ${idx + 1} end`}
                          className="w-32"
                          value={iv.endTime}
                          onChange={(e) => update(d, list.map((x, i) => (i === idx ? { ...x, endTime: e.target.value } : x)))}
                        />
                        <Button type="button" variant="ghost" size="icon" aria-label={`Remove ${DAY_LABEL[d]} interval ${idx + 1}`} onClick={() => update(d, list.filter((_, i) => i !== idx))}>
                          <Trash2 aria-hidden="true" />
                        </Button>
                      </div>
                    ))}
                    {errors[d] && <p className="text-xs text-destructive">{errors[d]}</p>}
                  </div>
                  <div className="flex gap-1">
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      aria-label={`Add interval on ${DAY_LABEL[d]}`}
                      onClick={() => {
                        const last = list[list.length - 1];
                        const start = last ? last.endTime : '09:00';
                        const [h] = start.split(':').map(Number);
                        const end = `${String(Math.min(23, (h ?? 9) + 1)).padStart(2, '0')}:${start.split(':')[1] ?? '00'}`;
                        update(d, [...list, { startTime: start, endTime: end > start ? end : '23:59' }]);
                      }}
                    >
                      <Plus aria-hidden="true" />
                    </Button>
                    {enabled && (
                      <Button type="button" variant="ghost" size="icon" aria-label={`Copy ${DAY_LABEL[d]} hours to all weekdays`} title="Copy to weekdays" onClick={() => copyToWeekdays(d)}>
                        <Copy aria-hidden="true" />
                      </Button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
          {serverError && (
            <p role="alert" className="text-sm text-destructive">
              {serverError}
            </p>
          )}
          <Button type="submit" className="justify-self-start" loading={save.isPending}>
            Save weekly hours
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

function Exceptions({ timeZone }: { timeZone: string }) {
  const qc = useQueryClient();
  const today = dateKeyInZone(new Date(), timeZone);
  const to = addDaysToKey(today, 180);
  const [date, setDate] = useState(today);
  const [available, setAvailable] = useState(false);
  const [startTime, setStartTime] = useState('09:00');
  const [endTime, setEndTime] = useState('12:00');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<AvailabilityException | null>(null);

  const q = useQuery({ queryKey: ['availability-exceptions', today, to], queryFn: () => availabilityApi.exceptions(today, to) });
  const add = useMutation({
    mutationFn: () =>
      availabilityApi.addException({
        date,
        available,
        startTime: available ? startTime : undefined,
        endTime: available ? endTime : undefined,
        reason: reason.trim() || undefined,
      }),
    onSuccess: () => {
      toast.success('Date override added');
      setReason('');
      void qc.invalidateQueries({ queryKey: ['availability-exceptions'] });
    },
    onError: (e) => setError(errorMessage(e)),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!date) return setError('Pick a date');
    if (available && startTime >= endTime) return setError('End time must be after start time');
    add.mutate();
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Date overrides</CardTitle>
        <CardDescription>Block out days (holidays, travel) or add custom hours for a specific date.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-6">
        <form onSubmit={submit} className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4" noValidate>
          <div className="grid gap-1.5">
            <Label htmlFor="ex-date">Date</Label>
            <Input id="ex-date" type="date" min={today} value={date} onChange={(e) => setDate(e.target.value)} />
          </div>
          <div className="flex items-end gap-3 pb-2">
            <Switch id="ex-available" checked={available} onCheckedChange={setAvailable} />
            <Label htmlFor="ex-available">{available ? 'Available (custom hours)' : 'Unavailable all day'}</Label>
          </div>
          {available && (
            <>
              <div className="grid gap-1.5">
                <Label htmlFor="ex-start">From</Label>
                <Input id="ex-start" type="time" value={startTime} onChange={(e) => setStartTime(e.target.value)} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor="ex-end">To</Label>
                <Input id="ex-end" type="time" value={endTime} onChange={(e) => setEndTime(e.target.value)} />
              </div>
            </>
          )}
          <div className="grid gap-1.5 sm:col-span-2">
            <Label htmlFor="ex-reason">Reason (optional)</Label>
            <Input id="ex-reason" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />
          </div>
          <div className="flex items-end">
            <Button type="submit" loading={add.isPending}>
              <Plus aria-hidden="true" /> Add override
            </Button>
          </div>
          {error && (
            <p role="alert" className="text-sm text-destructive sm:col-span-2 lg:col-span-4">
              {error}
            </p>
          )}
        </form>
        {q.isPending ? (
          <ListSkeleton rows={2} />
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : q.data.length === 0 ? (
          <p className="flex items-center gap-2 text-sm text-muted-foreground">
            <CalendarOff className="size-4" aria-hidden="true" /> No upcoming overrides.
          </p>
        ) : (
          <ul className="divide-y rounded-lg border">
            {[...q.data].sort((a, b) => a.date.localeCompare(b.date)).map((ex) => (
              <li key={ex.id} className="flex flex-wrap items-center gap-3 px-3 py-2 text-sm">
                <span className="font-medium">{formatDateKey(ex.date, { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' })}</span>
                {ex.available ? (
                  <Badge variant="success">
                    {ex.startTime?.slice(0, 5)}–{ex.endTime?.slice(0, 5)}
                  </Badge>
                ) : (
                  <Badge variant="destructive">Unavailable</Badge>
                )}
                {ex.reason && <span className="text-muted-foreground">{ex.reason}</span>}
                <Button variant="ghost" size="icon" className="ml-auto" aria-label={`Delete override on ${ex.date}`} onClick={() => setDeleting(ex)}>
                  <Trash2 aria-hidden="true" />
                </Button>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(o) => !o && setDeleting(null)}
        title="Delete this date override?"
        confirmLabel="Delete"
        onConfirm={async () => {
          if (!deleting) return;
          await availabilityApi.deleteException(deleting.id);
          toast.success('Override deleted');
          await qc.invalidateQueries({ queryKey: ['availability-exceptions'] });
        }}
      />
    </Card>
  );
}

export default function AvailabilityPage() {
  const q = useQuery({ queryKey: ['availability'], queryFn: availabilityApi.get });
  return (
    <div className="grid gap-6">
      <PageHeader title="Availability" description="Set the hours your booking pages offer." />
      {q.isPending ? (
        <ListSkeleton rows={4} />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <>
          <WeeklyEditor key={JSON.stringify(q.data)} availability={q.data} />
          <Exceptions timeZone={q.data.timeZone || browserTimeZone()} />
        </>
      )}
    </div>
  );
}
