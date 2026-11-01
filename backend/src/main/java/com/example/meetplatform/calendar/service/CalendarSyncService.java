package com.example.meetplatform.calendar.service;

import com.example.meetplatform.booking.event.BookingEvents;
import com.example.meetplatform.booking.repository.BookingRepository;
import com.example.meetplatform.calendar.adapter.CalendarProvider;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.mapper.MeetingMapper;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors bookings into the host's connected external calendar. Runs after the booking commits, outside the
 * request's transaction, and never fails the booking: provider errors are logged and the ICS e-mail remains.
 */
@Service
public class CalendarSyncService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);

    private final CalendarConnectionService connections;
    private final BookingRepository bookings;
    private final MeetingRepository meetings;
    private final MeetingMapper meetingMapper;
    private final TransactionTemplate tx;

    public CalendarSyncService(CalendarConnectionService connections, BookingRepository bookings,
                               MeetingRepository meetings, MeetingMapper meetingMapper, TransactionTemplate tx) {
        this.connections = connections;
        this.bookings = bookings;
        this.meetings = meetings;
        this.meetingMapper = meetingMapper;
        this.tx = tx;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfirmed(BookingEvents.BookingConfirmed e) {
        push(e.bookingId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRescheduled(BookingEvents.BookingRescheduled e) {
        remove(e.previousBookingId());
        push(e.bookingId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancelled(BookingEvents.BookingCancelled e) {
        remove(e.bookingId());
    }

    private void push(UUID bookingId) {
        try {
            tx.executeWithoutResult(s -> bookings.findById(bookingId).ifPresent(booking ->
                    connections.activeCalendar(booking.getHostId()).ifPresent(calendar -> {
                        Meeting meeting = meetings.findById(booking.getMeetingId()).orElseThrow();
                        String eventId = calendar.provider().createEvent(calendar.accessToken(),
                                new CalendarProvider.CalendarEvent(meeting.getTitle(), booking.getNotes(),
                                        booking.getStartTime(), booking.getEndTime(), meetingMapper.joinUrl(meeting),
                                        List.of(booking.getInviteeEmail())));
                        booking.linkExternalEvent(calendar.provider().id(), eventId);
                    })));
        } catch (RuntimeException ex) {
            log.warn("Calendar sync failed for booking {}: {}", bookingId, ex.getMessage());
        }
    }

    private void remove(UUID bookingId) {
        try {
            tx.executeWithoutResult(s -> bookings.findById(bookingId)
                    .filter(b -> b.getExternalEventId() != null)
                    .ifPresent(booking -> connections.activeCalendar(booking.getHostId())
                            .filter(c -> c.provider().id().equals(booking.getExternalCalendarProvider()))
                            .ifPresent(calendar -> {
                                calendar.provider().deleteEvent(calendar.accessToken(), booking.getExternalEventId());
                                booking.linkExternalEvent(null, null);
                            })));
        } catch (RuntimeException ex) {
            log.warn("Calendar removal failed for booking {}: {}", bookingId, ex.getMessage());
        }
    }
}
