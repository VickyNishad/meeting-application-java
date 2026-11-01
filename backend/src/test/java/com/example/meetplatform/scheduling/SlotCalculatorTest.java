package com.example.meetplatform.scheduling;

import com.example.meetplatform.scheduling.service.SlotCalculator;
import com.example.meetplatform.scheduling.service.SlotCalculator.DateException;
import com.example.meetplatform.scheduling.service.SlotCalculator.Interval;
import com.example.meetplatform.scheduling.service.SlotCalculator.Settings;
import com.example.meetplatform.scheduling.service.SlotCalculator.Slot;
import com.example.meetplatform.scheduling.service.SlotCalculator.WeeklyRule;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlotCalculatorTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final Settings HOUR_SLOTS = new Settings(Duration.ofMinutes(60), Duration.ofMinutes(60),
            Duration.ZERO, Duration.ofDays(365), Duration.ZERO, Duration.ZERO);

    private static List<WeeklyRule> everyDay(LocalTime start, LocalTime end) {
        return EnumSet.allOf(DayOfWeek.class).stream().map(d -> new WeeklyRule(d, start, end)).toList();
    }

    private static List<Slot> day(ZoneId hostZone, List<WeeklyRule> rules, List<DateException> exceptions,
                                  List<Interval> busy, Settings settings, LocalDate date, ZoneId viewerZone) {
        Instant from = date.atStartOfDay(viewerZone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(viewerZone).toInstant();
        return SlotCalculator.compute(hostZone, rules, exceptions, busy, settings,
                Instant.parse("2026-01-01T00:00:00Z"), from, to);
    }

    @Test
    void workingHoursStayLocalAcrossSpringForward() {
        // 2026-03-08: US clocks jump from 02:00 to 03:00 local time.
        LocalDate before = LocalDate.of(2026, 3, 7);
        LocalDate after = LocalDate.of(2026, 3, 9);
        var rules = everyDay(LocalTime.of(9, 0), LocalTime.of(12, 0));

        List<Slot> pre = day(NEW_YORK, rules, List.of(), List.of(), HOUR_SLOTS, before, NEW_YORK);
        List<Slot> post = day(NEW_YORK, rules, List.of(), List.of(), HOUR_SLOTS, after, NEW_YORK);

        assertThat(pre).hasSize(3);
        assertThat(post).hasSize(3);
        // 09:00 EST = 14:00Z, 09:00 EDT = 13:00Z: same local time, different UTC instant.
        assertThat(pre.getFirst().start()).isEqualTo(Instant.parse("2026-03-07T14:00:00Z"));
        assertThat(post.getFirst().start()).isEqualTo(Instant.parse("2026-03-09T13:00:00Z"));
    }

    @Test
    void nightWindowSpanningTheGapHasOneHourLess() {
        // 00:00-04:00 local on the spring-forward night contains only 3 real hours.
        var rules = everyDay(LocalTime.of(0, 0), LocalTime.of(4, 0));
        List<Slot> slots = day(NEW_YORK, rules, List.of(), List.of(), HOUR_SLOTS, LocalDate.of(2026, 3, 8), NEW_YORK);
        assertThat(slots).hasSize(3);
        assertThat(slots).allSatisfy(s -> assertThat(Duration.between(s.start(), s.end())).isEqualTo(Duration.ofHours(1)));
    }

    @Test
    void nightWindowSpanningTheOverlapHasOneHourMore() {
        // 2026-11-01: 01:00-02:00 happens twice; 00:00-04:00 local is 5 real hours.
        var rules = everyDay(LocalTime.of(0, 0), LocalTime.of(4, 0));
        List<Slot> slots = day(NEW_YORK, rules, List.of(), List.of(), HOUR_SLOTS, LocalDate.of(2026, 11, 1), NEW_YORK);
        assertThat(slots).hasSize(5);
    }

    @Test
    void slotsAreReportedForTheViewersCalendarDay() {
        // Host in New York works 20:00-22:00; for a viewer in Kolkata (UTC+5:30) those slots fall on the next date.
        var rules = everyDay(LocalTime.of(20, 0), LocalTime.of(22, 0));
        LocalDate kolkataDate = LocalDate.of(2026, 6, 16);
        List<Slot> slots = day(NEW_YORK, rules, List.of(), List.of(), HOUR_SLOTS, kolkataDate, KOLKATA);
        assertThat(slots).isNotEmpty();
        assertThat(slots).allSatisfy(s ->
                assertThat(ZonedDateTime.ofInstant(s.start(), KOLKATA).toLocalDate()).isEqualTo(kolkataDate));
        // 20:00 EDT on June 15 = 05:30 IST on June 16.
        assertThat(ZonedDateTime.ofInstant(slots.getFirst().start(), KOLKATA).toLocalTime()).isEqualTo(LocalTime.of(5, 30));
    }

    @Test
    void buffersBlockAdjacentSlots() {
        var rules = everyDay(LocalTime.of(9, 0), LocalTime.of(13, 0));
        LocalDate date = LocalDate.of(2026, 6, 10);
        Instant busyStart = ZonedDateTime.of(date, LocalTime.of(11, 0), NEW_YORK).toInstant();
        List<Interval> busy = List.of(new Interval(busyStart, busyStart.plus(Duration.ofHours(1))));
        Settings withBuffer = new Settings(Duration.ofMinutes(60), Duration.ofMinutes(60), Duration.ZERO,
                Duration.ofDays(365), Duration.ofMinutes(15), Duration.ofMinutes(15));

        List<Slot> slots = day(NEW_YORK, rules, List.of(), busy, withBuffer, date, NEW_YORK);
        // 09:00 is free; 10:00 (+15 min after-buffer touches 11:00) and 12:00 (15 min before-buffer) are blocked.
        assertThat(slots).extracting(s -> ZonedDateTime.ofInstant(s.start(), NEW_YORK).toLocalTime())
                .containsExactly(LocalTime.of(9, 0));
    }

    @Test
    void minimumNoticeAndMaximumAdvanceAreEnforced() {
        var rules = everyDay(LocalTime.of(9, 0), LocalTime.of(17, 0));
        Instant now = ZonedDateTime.of(2026, 6, 10, 10, 30, 0, 0, NEW_YORK).toInstant();
        Settings settings = new Settings(Duration.ofMinutes(30), Duration.ofMinutes(30), Duration.ofHours(2),
                Duration.ofDays(2), Duration.ZERO, Duration.ZERO);
        List<Slot> slots = SlotCalculator.compute(NEW_YORK, rules, List.of(), List.of(), settings, now,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(10)));
        assertThat(slots.getFirst().start()).isEqualTo(ZonedDateTime.of(2026, 6, 10, 12, 30, 0, 0, NEW_YORK).toInstant());
        assertThat(slots.getLast().start()).isBefore(now.plus(Duration.ofDays(2)));
    }

    @Test
    void dateExceptionsOverrideWeeklyRules() {
        var rules = everyDay(LocalTime.of(9, 0), LocalTime.of(12, 0));
        LocalDate dayOff = LocalDate.of(2026, 6, 10);
        LocalDate special = LocalDate.of(2026, 6, 11);
        LocalDate partial = LocalDate.of(2026, 6, 12);
        List<DateException> exceptions = List.of(
                new DateException(dayOff, false, null, null),
                new DateException(special, true, LocalTime.of(18, 0), LocalTime.of(19, 0)),
                new DateException(partial, false, LocalTime.of(10, 0), LocalTime.of(11, 0)));

        assertThat(day(NEW_YORK, rules, exceptions, List.of(), HOUR_SLOTS, dayOff, NEW_YORK)).isEmpty();
        assertThat(day(NEW_YORK, rules, exceptions, List.of(), HOUR_SLOTS, special, NEW_YORK))
                .extracting(s -> ZonedDateTime.ofInstant(s.start(), NEW_YORK).toLocalTime())
                .containsExactly(LocalTime.of(18, 0));
        assertThat(day(NEW_YORK, rules, exceptions, List.of(), HOUR_SLOTS, partial, NEW_YORK))
                .extracting(s -> ZonedDateTime.ofInstant(s.start(), NEW_YORK).toLocalTime())
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(11, 0));
    }
}
