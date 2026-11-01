import type { Slot } from './api/types';

/** Browser's resolved IANA time zone (falls back to UTC). */
export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

const FALLBACK_ZONES = [
  'UTC',
  'America/Los_Angeles',
  'America/Denver',
  'America/Chicago',
  'America/New_York',
  'America/Sao_Paulo',
  'Europe/London',
  'Europe/Berlin',
  'Europe/Paris',
  'Africa/Johannesburg',
  'Asia/Dubai',
  'Asia/Kolkata',
  'Asia/Singapore',
  'Asia/Tokyo',
  'Australia/Sydney',
  'Pacific/Auckland',
];

/** All IANA zones known to the browser (Intl.supportedValuesOf), always including UTC and the browser zone. */
export function listTimeZones(): string[] {
  let zones: string[] = [];
  try {
    const intl = Intl as unknown as { supportedValuesOf?: (key: string) => string[] };
    zones = intl.supportedValuesOf ? intl.supportedValuesOf('timeZone') : [];
  } catch {
    zones = [];
  }
  if (zones.length === 0) zones = FALLBACK_ZONES;
  const set = new Set(zones);
  set.add('UTC');
  set.add(browserTimeZone());
  return [...set].sort((a, b) => a.localeCompare(b));
}

interface ZonedParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
  weekday: number; // 0=Sun..6=Sat
}

const WEEKDAYS: Record<string, number> = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };

const partsFormatterCache = new Map<string, Intl.DateTimeFormat>();
function partsFormatter(timeZone: string): Intl.DateTimeFormat {
  let f = partsFormatterCache.get(timeZone);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      weekday: 'short',
    });
    partsFormatterCache.set(timeZone, f);
  }
  return f;
}

export function zonedParts(date: Date, timeZone: string): ZonedParts {
  const out: Record<string, string> = {};
  for (const p of partsFormatter(timeZone).formatToParts(date)) out[p.type] = p.value;
  return {
    year: Number(out.year),
    month: Number(out.month),
    day: Number(out.day),
    hour: Number(out.hour) % 24,
    minute: Number(out.minute),
    second: Number(out.second),
    weekday: WEEKDAYS[out.weekday ?? 'Sun'] ?? 0,
  };
}

const pad = (n: number) => String(n).padStart(2, '0');

/** `YYYY-MM-DD` of the instant in the given zone. */
export function dateKeyInZone(date: Date, timeZone: string): string {
  const p = zonedParts(date, timeZone);
  return `${p.year}-${pad(p.month)}-${pad(p.day)}`;
}

/** `HH:mm` of the instant in the given zone. */
export function timeInZone(date: Date, timeZone: string): string {
  const p = zonedParts(date, timeZone);
  return `${pad(p.hour)}:${pad(p.minute)}`;
}

/** Offset (ms) of `timeZone` from UTC at the given instant. */
function zoneOffsetMs(date: Date, timeZone: string): number {
  const p = zonedParts(date, timeZone);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return asUtc - Math.floor(date.getTime() / 1000) * 1000;
}

/**
 * Converts a wall-clock date + time in an IANA zone to a UTC Date (DST-aware).
 * Non-existent local times (spring-forward gap) resolve to the instant after the gap.
 */
export function zonedLocalToUtc(dateKey: string, time: string, timeZone: string): Date {
  const [y, m, d] = dateKey.split('-').map(Number);
  const [hh, mm] = time.split(':').map(Number);
  const guess = Date.UTC(y ?? 1970, (m ?? 1) - 1, d ?? 1, hh ?? 0, mm ?? 0, 0);
  let ts = guess - zoneOffsetMs(new Date(guess), timeZone);
  // Second pass corrects for offset changes between the guess and the real instant.
  const offset2 = zoneOffsetMs(new Date(ts), timeZone);
  ts = guess - offset2;
  return new Date(ts);
}

/** Splits an ISO instant into `{date: YYYY-MM-DD, time: HH:mm}` in the given zone. */
export function utcToZonedLocal(iso: string, timeZone: string): { date: string; time: string } {
  const d = new Date(iso);
  return { date: dateKeyInZone(d, timeZone), time: timeInZone(d, timeZone) };
}

/** Adds days to a `YYYY-MM-DD` key (calendar arithmetic, zone independent). */
export function addDaysToKey(dateKey: string, days: number): string {
  const [y, m, d] = dateKey.split('-').map(Number);
  const dt = new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1, (d ?? 1) + days));
  return `${dt.getUTCFullYear()}-${pad(dt.getUTCMonth() + 1)}-${pad(dt.getUTCDate())}`;
}

export function formatDateTime(iso: string | undefined | null, timeZone?: string): string {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone,
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

export function formatDate(iso: string | undefined | null, timeZone?: string): string {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone }).format(new Date(iso));
  } catch {
    return iso;
  }
}

export function formatTime(iso: string, timeZone?: string): string {
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', timeZone }).format(new Date(iso));
}

/** Formats a `YYYY-MM-DD` key as a weekday + date label (no zone shifting). */
export function formatDateKey(dateKey: string, opts: Intl.DateTimeFormatOptions = { weekday: 'long', month: 'long', day: 'numeric' }): string {
  const [y, m, d] = dateKey.split('-').map(Number);
  return new Intl.DateTimeFormat(undefined, { ...opts, timeZone: 'UTC' }).format(
    new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1, d ?? 1, 12)),
  );
}

export function relativeTime(iso: string, now: Date = new Date()): string {
  const diff = new Date(iso).getTime() - now.getTime();
  const abs = Math.abs(diff);
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  const minutes = Math.round(diff / 60000);
  if (abs < 3600_000) return rtf.format(minutes, 'minute');
  const hours = Math.round(diff / 3600_000);
  if (abs < 86_400_000) return rtf.format(hours, 'hour');
  return rtf.format(Math.round(diff / 86_400_000), 'day');
}

export interface SlotView extends Slot {
  /** Localised time label in the viewer's zone, e.g. "9:30 AM". */
  label: string;
}

export interface SlotDay {
  /** `YYYY-MM-DD` in the viewer's zone. */
  dateKey: string;
  slots: SlotView[];
}

/**
 * Groups UTC slots by calendar day **in the viewer's time zone**, preserving chronological order.
 * A slot belongs to the day on which it starts in that zone.
 */
export function groupSlotsByDay(slots: Slot[], timeZone: string, locale?: string): SlotDay[] {
  const fmt = new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit', timeZone });
  const sorted = [...slots].sort((a, b) => new Date(a.start).getTime() - new Date(b.start).getTime());
  const days = new Map<string, SlotView[]>();
  for (const s of sorted) {
    const start = new Date(s.start);
    if (Number.isNaN(start.getTime())) continue;
    const key = dateKeyInZone(start, timeZone);
    const list = days.get(key) ?? [];
    list.push({ ...s, label: fmt.format(start) });
    days.set(key, list);
  }
  return [...days.entries()].map(([dateKey, list]) => ({ dateKey, slots: list }));
}

export const DAYS_OF_WEEK = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const;
