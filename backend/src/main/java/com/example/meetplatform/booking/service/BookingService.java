package com.example.meetplatform.booking.service;

import com.example.meetplatform.booking.dto.BookingDtos.BookingView;
import com.example.meetplatform.booking.dto.BookingDtos.CreateBookingRequest;
import com.example.meetplatform.booking.entity.Booking;
import com.example.meetplatform.booking.entity.BookingPage;
import com.example.meetplatform.booking.event.BookingEvents;
import com.example.meetplatform.booking.repository.BookingPageRepository;
import com.example.meetplatform.booking.repository.BookingRepository;
import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.response.PageParams;
import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.security.RateLimiter;
import com.example.meetplatform.common.util.Tokens;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingStatus;
import com.example.meetplatform.meeting.mapper.MeetingMapper;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.meeting.service.MeetingService;
import com.example.meetplatform.scheduling.service.AvailabilityService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Public bookings. Availability is checked against the computed slots, and the final guarantee against double
 * booking is the PostgreSQL exclusion constraint on (host, blocked time range): two concurrent requests for the
 * same slot both pass the slot check, but only one insert can commit; the other surfaces as SLOT_UNAVAILABLE.
 */
@Service
public class BookingService {

    private final BookingRepository bookings;
    private final BookingPageRepository pages;
    private final BookingPageService pageService;
    private final SlotService slots;
    private final AvailabilityService availability;
    private final MeetingService meetingService;
    private final MeetingRepository meetings;
    private final MeetingMapper meetingMapper;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public BookingService(BookingRepository bookings, BookingPageRepository pages, BookingPageService pageService,
                          SlotService slots, AvailabilityService availability, MeetingService meetingService,
                          MeetingRepository meetings, MeetingMapper meetingMapper, RateLimiter rateLimiter,
                          AuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.bookings = bookings;
        this.pages = pages;
        this.pageService = pageService;
        this.slots = slots;
        this.availability = availability;
        this.meetingService = meetingService;
        this.meetings = meetings;
        this.meetingMapper = meetingMapper;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public BookingView create(CreateBookingRequest request, String clientIp) {
        rateLimiter.check("booking-ip", clientIp, 20, Duration.ofHours(1));
        rateLimiter.check("booking-email", request.inviteeEmail().toLowerCase(Locale.ROOT), 10, Duration.ofHours(1));
        BookingPage page = pageService.activeBySlug(request.slug());
        String manageToken = Tokens.randomSecret(24);
        Booking booking = insert(page, request.startTime(), request.inviteeName().trim(),
                request.inviteeEmail().trim().toLowerCase(Locale.ROOT), request.inviteeTimeZone(),
                blankToNull(request.notes()), manageToken, null);
        audit.record(null, null, AuditActions.BOOKING_CREATED, AuditActions.RESOURCE_BOOKING, booking.getId(),
                Map.of("bookingPageId", page.getId()));
        events.publishEvent(new BookingEvents.BookingConfirmed(booking.getId()));
        return view(booking, page, manageToken);
    }

    @Transactional(readOnly = true)
    public BookingView byManageToken(String token) {
        Booking booking = requireByToken(token);
        return view(booking, pages.findById(booking.getBookingPageId()).orElse(null), null);
    }

    @Transactional
    public void cancelByInvitee(String token, String reason) {
        cancel(requireByToken(token), reason, false);
    }

    @Transactional
    public void cancelByHost(CurrentUser user, UUID bookingId, String reason) {
        cancel(requireOwned(user, bookingId), reason, true);
    }

    @Transactional
    public BookingView reschedule(String token, Instant newStart) {
        Booking old = requireByToken(token);
        Instant now = clock.instant();
        if (!old.isModifiable(now)) {
            throw new ApiException(ErrorCode.BOOKING_NOT_MODIFIABLE, "This booking can no longer be changed.");
        }
        BookingPage page = pages.findById(old.getBookingPageId())
                .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_MODIFIABLE, "The booking page no longer exists."));
        if (old.getStartTime().equals(newStart)) {
            throw new ApiException(ErrorCode.BOOKING_NOT_MODIFIABLE, "The booking is already at this time.");
        }
        // Release the old time first so the new slot may overlap it (e.g. moving by 15 minutes).
        old.setStatus(Booking.Status.RESCHEDULED);
        bookings.flush();
        String manageToken = Tokens.randomSecret(24);
        Booking replacement = insertWithMeeting(page, newStart, old, manageToken);
        audit.record(null, null, AuditActions.BOOKING_RESCHEDULED, AuditActions.RESOURCE_BOOKING, replacement.getId(),
                Map.of("previousBookingId", old.getId()));
        events.publishEvent(new BookingEvents.BookingRescheduled(old.getId(), replacement.getId()));
        return view(replacement, page, manageToken);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingView> forHost(CurrentUser user, String scope, Integer page, Integer size) {
        var pageable = PageParams.of(page, size, Sort.unsorted());
        Page<Booking> result = "past".equalsIgnoreCase(scope)
                ? bookings.findPast(user.userId(), clock.instant(), pageable)
                : bookings.findUpcoming(user.userId(), clock.instant(), pageable);
        return PageResponse.of(result, b -> view(b, pages.findById(b.getBookingPageId()).orElse(null), null));
    }

    @Transactional(readOnly = true)
    public BookingView get(CurrentUser user, UUID bookingId) {
        Booking b = requireOwned(user, bookingId);
        return view(b, pages.findById(b.getBookingPageId()).orElse(null), null);
    }

    @Transactional(readOnly = true)
    public Booking require(UUID bookingId) {
        return bookings.findById(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
    }

    // ------------------------------------------------------------------ helpers

    private Booking insert(BookingPage page, Instant start, String name, String email, String timeZone, String notes,
                           String manageToken, UUID rescheduledFrom) {
        if (!slots.isOffered(page, start)) {
            throw new ApiException(ErrorCode.SLOT_UNAVAILABLE, "The selected time slot is no longer available.");
        }
        Instant end = start.plus(Duration.ofMinutes(page.getDurationMinutes()));
        Booking booking = new Booking(page.getId(), page.getOwnerId(), start, end,
                start.minus(Duration.ofMinutes(page.getBufferBeforeMinutes())),
                end.plus(Duration.ofMinutes(page.getBufferAfterMinutes())), name, email, timeZone, notes,
                Tokens.sha256(manageToken));
        booking.setRescheduledFromId(rescheduledFrom);
        // Flush now so a concurrent overlapping booking fails here (exclusion violation -> 409 SLOT_UNAVAILABLE).
        flushContended(() -> bookings.saveAndFlush(booking));
        Meeting meeting = meetingService.createForBooking(page.getOwnerId(), page.getTitle() + " with " + name,
                notes, start, end, availability.timeZoneOf(page.getOwnerId()), page.getMediaMode(), email);
        booking.setMeetingId(meeting.getId());
        // The UPDATE rewrites the blocked range, so PostgreSQL re-runs the exclusion check; flush it here rather
        // than at commit so a deadlock on it is handled like the insert's.
        flushContended(bookings::flush);
        return booking;
    }

    private static void flushContended(Runnable flush) {
        try {
            flush.run();
        } catch (PessimisticLockingFailureException contended) {
            // Concurrent writes for neighbouring slots (buffers overlap) can deadlock inside the exclusion check
            // (SQLSTATE 40P01) or time out waiting for its lock. PostgreSQL has already aborted this transaction;
            // to the invitee it is the same outcome as losing the race for the slot.
            throw new ApiException(ErrorCode.SLOT_UNAVAILABLE, "The selected time slot is no longer available.",
                    contended);
        }
    }

    private Booking insertWithMeeting(BookingPage page, Instant start, Booking old, String manageToken) {
        if (old.getMeetingId() != null) {
            meetings.findById(old.getMeetingId()).filter(m -> m.getStatus() == MeetingStatus.SCHEDULED)
                    .ifPresent(m -> m.cancel(clock.instant(), "Rescheduled"));
            meetings.flush();
        }
        return insert(page, start, old.getInviteeName(), old.getInviteeEmail(), old.getInviteeTimeZone(),
                old.getNotes(), manageToken, old.getId());
    }

    private void cancel(Booking booking, String reason, boolean byHost) {
        Instant now = clock.instant();
        if (!booking.isModifiable(now)) {
            throw new ApiException(ErrorCode.BOOKING_NOT_MODIFIABLE, "This booking can no longer be changed.");
        }
        booking.cancel(now, blankToNull(reason));
        if (booking.getMeetingId() != null) {
            meetings.findById(booking.getMeetingId()).filter(m -> m.getStatus() == MeetingStatus.SCHEDULED)
                    .ifPresent(m -> m.cancel(now, "Booking cancelled"));
        }
        audit.record(null, null, AuditActions.BOOKING_CANCELLED, AuditActions.RESOURCE_BOOKING, booking.getId(),
                Map.of("byHost", byHost));
        events.publishEvent(new BookingEvents.BookingCancelled(booking.getId(), byHost));
    }

    private Booking requireByToken(String token) {
        if (token == null || token.length() > 128) {
            throw ApiException.notFound("Booking");
        }
        return bookings.findByManageTokenHash(Tokens.sha256(token)).orElseThrow(() -> ApiException.notFound("Booking"));
    }

    private Booking requireOwned(CurrentUser user, UUID bookingId) {
        return bookings.findById(bookingId).filter(b -> b.getHostId().equals(user.userId()))
                .orElseThrow(() -> ApiException.notFound("Booking"));
    }

    private BookingView view(Booking b, BookingPage page, String manageToken) {
        String joinUrl = b.getMeetingId() == null ? null
                : meetings.findById(b.getMeetingId()).map(meetingMapper::joinUrl).orElse(null);
        return new BookingView(b.getId(), page == null ? null : page.getSlug(), page == null ? null : page.getTitle(),
                b.getStatus(), b.getStartTime(), b.getEndTime(), b.getInviteeName(), b.getInviteeEmail(),
                b.getInviteeTimeZone(), b.getNotes(), b.getMeetingId(), joinUrl, b.getCreatedAt(), manageToken);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
