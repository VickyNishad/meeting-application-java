import { describe, expect, it } from 'vitest';
import { addDaysToKey, dateKeyInZone, greeting, groupSlotsByDay, utcToZonedLocal, zonedLocalToUtc } from './time';

const slots = [
  // 2026-11-02 22:30Z = 2026-11-03 04:00 in Kolkata (+05:30) = 2026-11-02 17:30 in New York (EST, -05:00)
  { start: '2026-11-02T22:30:00Z', end: '2026-11-02T23:00:00Z' },
  { start: '2026-11-02T14:00:00Z', end: '2026-11-02T14:30:00Z' },
  { start: '2026-11-03T04:00:00Z', end: '2026-11-03T04:30:00Z' },
];

describe('groupSlotsByDay', () => {
  it('groups by calendar day in the viewer time zone (New York)', () => {
    const days = groupSlotsByDay(slots, 'America/New_York', 'en-US');
    expect(days.map((d) => d.dateKey)).toEqual(['2026-11-02']);
    // 14:00Z → 09:00, 22:30Z → 17:30, 04:00Z (Nov 3) → 23:00 Nov 2 — all on Nov 2 in New York
    expect(days).toHaveLength(1);
    expect(days[0]!.slots.map((s) => s.label)).toEqual(['9:00 AM', '5:30 PM', '11:00 PM']);
  });

  it('groups the same slots differently for a viewer in Kolkata', () => {
    const days = groupSlotsByDay(slots, 'Asia/Kolkata', 'en-US');
    // 14:00Z → 19:30 Nov 2; 22:30Z → 04:00 Nov 3; 04:00Z → 09:30 Nov 3
    expect(days.map((d) => d.dateKey)).toEqual(['2026-11-02', '2026-11-03']);
    expect(days[0]!.slots.map((s) => s.label)).toEqual(['7:30 PM']);
    expect(days[1]!.slots.map((s) => s.label)).toEqual(['4:00 AM', '9:30 AM']);
  });

  it('sorts chronologically and skips invalid timestamps', () => {
    const days = groupSlotsByDay([{ start: 'nope', end: 'nope' }, ...slots], 'UTC', 'en-US');
    expect(days.flatMap((d) => d.slots.map((s) => s.start))).toEqual([
      '2026-11-02T14:00:00Z',
      '2026-11-02T22:30:00Z',
      '2026-11-03T04:00:00Z',
    ]);
  });
});

describe('zoned conversions', () => {
  it('converts wall time in a zone to UTC across DST', () => {
    // New York: EDT (-4) in October, EST (-5) in November 2026 (DST ends Nov 1)
    expect(zonedLocalToUtc('2026-10-15', '09:00', 'America/New_York').toISOString()).toBe('2026-10-15T13:00:00.000Z');
    expect(zonedLocalToUtc('2026-11-15', '09:00', 'America/New_York').toISOString()).toBe('2026-11-15T14:00:00.000Z');
    expect(zonedLocalToUtc('2026-06-01', '09:30', 'Asia/Kolkata').toISOString()).toBe('2026-06-01T04:00:00.000Z');
  });

  it('round-trips UTC → zoned local', () => {
    expect(utcToZonedLocal('2026-11-15T14:00:00.000Z', 'America/New_York')).toEqual({ date: '2026-11-15', time: '09:00' });
    expect(dateKeyInZone(new Date('2026-12-31T20:00:00Z'), 'Pacific/Auckland')).toBe('2027-01-01');
  });

  it('adds days across month/year boundaries', () => {
    expect(addDaysToKey('2026-12-30', 3)).toBe('2027-01-02');
    expect(addDaysToKey('2028-02-28', 1)).toBe('2028-02-29');
  });
});

describe('greeting (dashboard)', () => {
  it('follows the time of day', () => {
    expect(greeting(6)).toBe('Good morning');
    expect(greeting(11)).toBe('Good morning');
    expect(greeting(12)).toBe('Good afternoon');
    expect(greeting(17)).toBe('Good afternoon');
    expect(greeting(18)).toBe('Good evening');
    expect(greeting(23)).toBe('Good evening');
  });
});
