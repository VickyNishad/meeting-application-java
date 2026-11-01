package com.example.meetplatform.scheduling.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Pure slot computation (no I/O, fully deterministic) so the time-zone and daylight-saving behaviour can be
 * unit-tested exhaustively.
 *
 * <p>Availability windows are defined in the host's IANA zone and converted to instants per calendar date, so a
 * 09:00-17:00 rule stays 09:00-17:00 local time across DST changes. Candidate starts step through each window in
 * real elapsed time. Local times that do not exist (spring-forward gap) are shifted forward by the gap, and
 * ambiguous ones (fall-back overlap) use the earlier offset, per {@link java.time.ZonedDateTime#of}.
 */
public final class SlotCalculator {

    private SlotCalculator() {
    }

    public record WeeklyRule(DayOfWeek day, LocalTime start, LocalTime end) {
    }

    /** {@code start/end} null means the whole day. {@code available=true} windows replace the weekly rules for that date. */
    public record DateException(LocalDate date, boolean available, LocalTime start, LocalTime end) {
    }

    public record Interval(Instant start, Instant end) {

        boolean overlaps(Instant otherStart, Instant otherEnd) {
            return start.isBefore(otherEnd) && otherStart.isBefore(end);
        }
    }

    public record Settings(Duration duration, Duration interval, Duration minNotice, Duration maxAdvance,
                           Duration bufferBefore, Duration bufferAfter) {
    }

    public record Slot(Instant start, Instant end) {
    }

    public static List<Slot> compute(ZoneId hostZone, List<WeeklyRule> rules, List<DateException> exceptions,
                                     List<Interval> busy, Settings settings, Instant now,
                                     Instant rangeStart, Instant rangeEnd) {
        Instant earliest = max(rangeStart, now.plus(settings.minNotice()));
        Instant latest = min(rangeEnd, now.plus(settings.maxAdvance()));
        if (!earliest.isBefore(latest)) {
            return List.of();
        }
        Map<LocalDate, List<DateException>> byDate = exceptions.stream()
                .collect(java.util.stream.Collectors.groupingBy(DateException::date));

        TreeSet<Slot> slots = new TreeSet<>(Comparator.comparing(Slot::start));
        LocalDate first = LocalDate.ofInstant(earliest, hostZone).minusDays(1);
        LocalDate last = LocalDate.ofInstant(latest, hostZone).plusDays(1);
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            List<DateException> dayExceptions = byDate.getOrDefault(date, List.of());
            if (dayExceptions.stream().anyMatch(e -> !e.available() && e.start() == null)) {
                continue; // whole day off
            }
            List<Interval> blocked = new ArrayList<>(busy);
            for (DateException e : dayExceptions) {
                if (!e.available() && e.start() != null) {
                    blocked.add(new Interval(toInstant(date, e.start(), hostZone), toInstant(date, e.end(), hostZone)));
                }
            }
            for (Interval window : windows(date, rules, dayExceptions, hostZone)) {
                Instant candidate = window.start();
                while (!candidate.plus(settings.duration()).isAfter(window.end())) {
                    Instant end = candidate.plus(settings.duration());
                    if (!candidate.isBefore(earliest) && candidate.isBefore(latest)
                            && isFree(blocked, candidate.minus(settings.bufferBefore()), end.plus(settings.bufferAfter()))) {
                        slots.add(new Slot(candidate, end));
                    }
                    candidate = candidate.plus(settings.interval());
                }
            }
        }
        return List.copyOf(slots);
    }

    private static List<Interval> windows(LocalDate date, List<WeeklyRule> rules, List<DateException> dayExceptions,
                                          ZoneId zone) {
        List<Interval> windows = new ArrayList<>();
        List<DateException> overrides = dayExceptions.stream().filter(DateException::available).toList();
        if (!overrides.isEmpty()) {
            for (DateException e : overrides) {
                windows.add(new Interval(toInstant(date, e.start(), zone), toInstant(date, e.end(), zone)));
            }
            return windows;
        }
        for (WeeklyRule rule : rules) {
            if (rule.day() == date.getDayOfWeek()) {
                windows.add(new Interval(toInstant(date, rule.start(), zone), toInstant(date, rule.end(), zone)));
            }
        }
        return windows;
    }

    private static boolean isFree(List<Interval> blocked, Instant start, Instant end) {
        for (Interval b : blocked) {
            if (b.overlaps(start, end)) {
                return false;
            }
        }
        return true;
    }

    private static Instant toInstant(LocalDate date, LocalTime time, ZoneId zone) {
        return java.time.ZonedDateTime.of(date, time, zone).toInstant();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
