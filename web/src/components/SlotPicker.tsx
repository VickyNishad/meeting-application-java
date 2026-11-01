import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CalendarX2, Loader2 } from 'lucide-react';
import { Button } from './ui/button';
import { ErrorState } from './common';
import { bookingPagesApi } from '@/lib/api/endpoints';
import type { Slot } from '@/lib/api/types';
import { addDaysToKey, dateKeyInZone, formatDateKey, groupSlotsByDay, type SlotDay } from '@/lib/time';
import { cn } from '@/lib/utils';

/** Pure rendering of slots grouped by day in the viewer's zone. */
export function SlotList({
  days,
  selected,
  onSelect,
}: {
  days: SlotDay[];
  selected?: string | null;
  onSelect: (slot: Slot) => void;
}) {
  if (days.length === 0) {
    return <p className="text-sm text-muted-foreground">No available times.</p>;
  }
  return (
    <div className="grid gap-5">
      {days.map((day) => (
        <section key={day.dateKey} aria-labelledby={`day-${day.dateKey}`}>
          <h3 id={`day-${day.dateKey}`} className="mb-2 text-sm font-semibold" data-testid="slot-day">
            {formatDateKey(day.dateKey)}
          </h3>
          <ul className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            {day.slots.map((s) => (
              <li key={s.start}>
                <Button
                  type="button"
                  variant={selected === s.start ? 'default' : 'outline'}
                  className="w-full"
                  aria-pressed={selected === s.start}
                  onClick={() => onSelect(s)}
                >
                  {s.label}
                </Button>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}

/**
 * Date strip over the next `days` days (in the viewer's zone) + times for the selected day.
 * Slots come from GET /booking-pages/public/{slug}/slots with the viewer's time zone.
 */
export function SlotPicker({
  slug,
  timeZone,
  days = 30,
  selected,
  onSelect,
  exclude,
}: {
  slug: string;
  timeZone: string;
  days?: number;
  selected: string | null;
  onSelect: (slot: Slot) => void;
  /** Slot start instants to hide (e.g. the booking being rescheduled). */
  exclude?: string[];
}) {
  const span = Math.min(Math.max(days, 1), 31);
  const from = dateKeyInZone(new Date(), timeZone);
  const to = addDaysToKey(from, span - 1);
  const q = useQuery({
    queryKey: ['slots', slug, from, to, timeZone],
    queryFn: () => bookingPagesApi.slots(slug, from, to, timeZone),
    // Availability changes with every booking: always refetch when a picker mounts.
    staleTime: 0,
    refetchOnMount: 'always',
  });
  const excludeKey = (exclude ?? []).map((e) => new Date(e).getTime()).join(',');
  const grouped = useMemo(() => {
    const hidden = new Set(excludeKey ? excludeKey.split(',').map(Number) : []);
    const slots = (q.data?.slots ?? []).filter((s) => !hidden.has(new Date(s.start).getTime()));
    return groupSlotsByDay(slots, timeZone);
  }, [q.data, timeZone, excludeKey]);
  const byDay = useMemo(() => new Map(grouped.map((d) => [d.dateKey, d])), [grouped]);
  const dateKeys = useMemo(() => Array.from({ length: span }, (_, i) => addDaysToKey(from, i)), [from, span]);
  const [pickedDay, setPickedDay] = useState<string | null>(null);
  const activeDay = pickedDay && byDay.has(pickedDay) ? pickedDay : (grouped[0]?.dateKey ?? null);

  if (q.isPending) {
    return (
      <div className="flex justify-center py-10" role="status">
        <Loader2 className="size-6 animate-spin text-muted-foreground" aria-label="Loading available times" />
      </div>
    );
  }
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (grouped.length === 0) {
    return (
      <div className="flex flex-col items-center gap-2 py-10 text-center text-sm text-muted-foreground">
        <CalendarX2 className="size-8" aria-hidden="true" />
        No times are available in the next {span} days.
      </div>
    );
  }
  const day = activeDay ? byDay.get(activeDay) : undefined;
  return (
    <div className="grid gap-4">
      <div className="-mx-1 flex gap-2 overflow-x-auto px-1 pb-2" role="listbox" aria-label="Choose a date">
        {dateKeys.map((k) => {
          const has = byDay.has(k);
          const active = k === activeDay;
          return (
            <button
              key={k}
              type="button"
              role="option"
              aria-selected={active}
              disabled={!has}
              onClick={() => setPickedDay(k)}
              className={cn(
                'flex w-16 shrink-0 flex-col items-center rounded-lg border px-2 py-2 text-xs transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                active ? 'border-primary bg-primary text-primary-foreground' : has ? 'hover:border-primary hover:bg-accent' : 'cursor-not-allowed opacity-40',
              )}
            >
              <span>{formatDateKey(k, { weekday: 'short' })}</span>
              <span className="text-lg font-semibold">{Number(k.slice(8, 10))}</span>
              <span>{formatDateKey(k, { month: 'short' })}</span>
            </button>
          );
        })}
      </div>
      {day && <SlotList days={[day]} selected={selected} onSelect={onSelect} />}
    </div>
  );
}
