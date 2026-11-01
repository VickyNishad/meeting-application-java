package com.example.meetplatform.booking.service;

import com.example.meetplatform.booking.entity.BookingPage;
import com.example.meetplatform.booking.repository.BookingRepository;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.scheduling.service.AvailabilityService;
import com.example.meetplatform.scheduling.service.SlotCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Loads a host's rules, exceptions and busy time and delegates to the pure {@link SlotCalculator}. */
@Service
public class SlotService {

    private final AvailabilityService availability;
    private final BookingRepository bookings;
    private final MeetingRepository meetings;
    private final Clock clock;

    public SlotService(AvailabilityService availability, BookingRepository bookings, MeetingRepository meetings,
                       Clock clock) {
        this.availability = availability;
        this.bookings = bookings;
        this.meetings = meetings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SlotCalculator.Slot> available(BookingPage page, Instant rangeStart, Instant rangeEnd) {
        ZoneId hostZone = ZoneId.of(availability.timeZoneOf(page.getOwnerId()));
        Duration bufferBefore = Duration.ofMinutes(page.getBufferBeforeMinutes());
        Duration bufferAfter = Duration.ofMinutes(page.getBufferAfterMinutes());
        Instant busyFrom = rangeStart.minus(Duration.ofDays(1));
        Instant busyTo = rangeEnd.plus(Duration.ofDays(1));

        List<SlotCalculator.Interval> busy = new ArrayList<>();
        bookings.findConfirmedOverlapping(page.getOwnerId(), busyFrom, busyTo)
                .forEach(b -> busy.add(new SlotCalculator.Interval(b.getBlockedStart(), b.getBlockedEnd())));
        meetings.findBusyForHost(page.getOwnerId(), busyFrom, busyTo)
                .forEach(m -> busy.add(new SlotCalculator.Interval(m.getStartTime(), m.getEndTime())));

        LocalDate firstDate = LocalDate.ofInstant(rangeStart, hostZone).minusDays(1);
        LocalDate lastDate = LocalDate.ofInstant(rangeEnd, hostZone).plusDays(1);
        SlotCalculator.Settings settings = new SlotCalculator.Settings(
                Duration.ofMinutes(page.getDurationMinutes()), Duration.ofMinutes(page.getSlotIntervalMinutes()),
                Duration.ofMinutes(page.getMinNoticeMinutes()), Duration.ofDays(page.getMaxAdvanceDays()),
                bufferBefore, bufferAfter);
        return SlotCalculator.compute(hostZone, availability.weeklyRules(page.getOwnerId()),
                availability.dateExceptions(page.getOwnerId(), firstDate, lastDate), busy, settings, clock.instant(),
                rangeStart, rangeEnd);
    }

    /** Whether {@code start} is currently an offered slot of the page. */
    @Transactional(readOnly = true)
    public boolean isOffered(BookingPage page, Instant start) {
        Instant from = start.minus(Duration.ofDays(1));
        Instant to = start.plus(Duration.ofDays(1));
        return available(page, from, to).stream().anyMatch(s -> s.start().equals(start));
    }
}
