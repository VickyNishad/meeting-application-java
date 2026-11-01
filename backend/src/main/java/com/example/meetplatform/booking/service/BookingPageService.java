package com.example.meetplatform.booking.service;

import com.example.meetplatform.booking.dto.BookingDtos.BookingPageRequest;
import com.example.meetplatform.booking.dto.BookingDtos.BookingPageView;
import com.example.meetplatform.booking.dto.BookingDtos.PublicBookingPage;
import com.example.meetplatform.booking.dto.BookingDtos.SlotView;
import com.example.meetplatform.booking.dto.BookingDtos.SlotsResponse;
import com.example.meetplatform.booking.dto.BookingDtos.UpdateBookingPageRequest;
import com.example.meetplatform.booking.entity.Booking;
import com.example.meetplatform.booking.entity.BookingPage;
import com.example.meetplatform.booking.repository.BookingPageRepository;
import com.example.meetplatform.booking.repository.BookingRepository;
import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.security.RateLimiter;
import com.example.meetplatform.common.validation.TimeZones;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.service.UserService;
import com.example.meetplatform.scheduling.service.AvailabilityService;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingPageService {

    private static final int MAX_RANGE_DAYS = 31;

    private final BookingPageRepository pages;
    private final BookingRepository bookings;
    private final SlotService slots;
    private final AvailabilityService availability;
    private final UserService users;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public BookingPageService(BookingPageRepository pages, BookingRepository bookings, SlotService slots,
                              AvailabilityService availability, UserService users, AuditService audit,
                              RateLimiter rateLimiter, Clock clock) {
        this.pages = pages;
        this.bookings = bookings;
        this.slots = slots;
        this.availability = availability;
        this.users = users;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @Transactional
    public BookingPageView create(CurrentUser user, BookingPageRequest r) {
        if (pages.existsBySlug(r.slug())) {
            throw new ApiException(ErrorCode.SLUG_TAKEN, "This booking link is already taken.");
        }
        BookingPage page = new BookingPage(user.userId(), r.slug(), r.title().trim(), r.durationMinutes());
        page.setDescription(r.description());
        page.setSlotIntervalMinutes(r.slotIntervalMinutes() == null ? r.durationMinutes() : r.slotIntervalMinutes());
        if (r.minNoticeMinutes() != null) {
            page.setMinNoticeMinutes(r.minNoticeMinutes());
        }
        if (r.maxAdvanceDays() != null) {
            page.setMaxAdvanceDays(r.maxAdvanceDays());
        }
        page.setBufferBeforeMinutes(r.bufferBeforeMinutes() == null ? 0 : r.bufferBeforeMinutes());
        page.setBufferAfterMinutes(r.bufferAfterMinutes() == null ? 0 : r.bufferAfterMinutes());
        if (r.mediaMode() != null) {
            page.setMediaMode(r.mediaMode());
        }
        if (r.active() != null) {
            page.setActive(r.active());
        }
        pages.save(page);
        audit.record(AuditActions.BOOKING_PAGE_CREATED, AuditActions.RESOURCE_BOOKING_PAGE, page.getId(),
                Map.of("slug", page.getSlug()));
        return view(page);
    }

    @Transactional(readOnly = true)
    public List<BookingPageView> mine(CurrentUser user) {
        return pages.findByOwnerIdOrderByCreatedAtAsc(user.userId()).stream().map(BookingPageService::view).toList();
    }

    @Transactional(readOnly = true)
    public BookingPageView get(CurrentUser user, UUID id) {
        return view(owned(user, id));
    }

    @Transactional
    public BookingPageView update(CurrentUser user, UUID id, UpdateBookingPageRequest r) {
        BookingPage page = owned(user, id);
        if (r.version() != null && !r.version().equals(page.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(BookingPage.class, id);
        }
        if (r.slug() != null && !r.slug().equals(page.getSlug())) {
            if (pages.existsBySlug(r.slug())) {
                throw new ApiException(ErrorCode.SLUG_TAKEN, "This booking link is already taken.");
            }
            page.setSlug(r.slug());
        }
        if (r.title() != null) {
            page.setTitle(r.title().trim());
        }
        if (r.description() != null) {
            page.setDescription(r.description().isBlank() ? null : r.description());
        }
        if (r.durationMinutes() != null) {
            page.setDurationMinutes(r.durationMinutes());
        }
        if (r.slotIntervalMinutes() != null) {
            page.setSlotIntervalMinutes(r.slotIntervalMinutes());
        }
        if (r.minNoticeMinutes() != null) {
            page.setMinNoticeMinutes(r.minNoticeMinutes());
        }
        if (r.maxAdvanceDays() != null) {
            page.setMaxAdvanceDays(r.maxAdvanceDays());
        }
        if (r.bufferBeforeMinutes() != null) {
            page.setBufferBeforeMinutes(r.bufferBeforeMinutes());
        }
        if (r.bufferAfterMinutes() != null) {
            page.setBufferAfterMinutes(r.bufferAfterMinutes());
        }
        if (r.mediaMode() != null) {
            page.setMediaMode(r.mediaMode());
        }
        if (r.active() != null) {
            page.setActive(r.active());
        }
        pages.flush();
        return view(page);
    }

    /**
     * Deleting a page cascades to its bookings, so it is refused while confirmed bookings are still upcoming
     * (their meetings, invitee e-mails and reminders would silently go stale). Cancel them or deactivate the page.
     */
    @Transactional
    public void delete(CurrentUser user, UUID id) {
        // Row lock: a booking being inserted concurrently either commits first (and is seen below) or fails.
        BookingPage page = pages.findByIdForUpdate(id).filter(p -> p.getOwnerId().equals(user.userId()))
                .orElseThrow(() -> ApiException.notFound("Booking page"));
        if (bookings.existsByBookingPageIdAndStatusAndEndTimeAfter(page.getId(), Booking.Status.CONFIRMED,
                clock.instant())) {
            throw new ApiException(ErrorCode.BOOKING_PAGE_HAS_UPCOMING_BOOKINGS, "This booking page still has upcoming"
                    + " confirmed bookings. Cancel them first, or deactivate the page instead of deleting it.");
        }
        pages.delete(page);
    }

    @Transactional(readOnly = true)
    public PublicBookingPage publicPage(String slug) {
        BookingPage page = activeBySlug(slug);
        User host = users.require(page.getOwnerId());
        return new PublicBookingPage(page.getSlug(), page.getTitle(), page.getDescription(), page.getDurationMinutes(),
                host.getDisplayName(), availability.timeZoneOf(host.getId()), page.getMediaMode());
    }

    @Transactional(readOnly = true)
    public SlotsResponse publicSlots(String slug, LocalDate from, LocalDate to, String timeZone, String clientIp) {
        rateLimiter.check("slots", clientIp, 300, Duration.ofMinutes(10));
        ZoneId viewerZone = TimeZones.require(timeZone);
        if (to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "'to' must not be before 'from'.");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "At most " + MAX_RANGE_DAYS + " days per request.");
        }
        BookingPage page = activeBySlug(slug);
        List<SlotView> result = slots.available(page, from.atStartOfDay(viewerZone).toInstant(),
                to.plusDays(1).atStartOfDay(viewerZone).toInstant()).stream()
                .map(s -> new SlotView(s.start(), s.end())).toList();
        return new SlotsResponse(timeZone, result);
    }

    BookingPage activeBySlug(String slug) {
        return pages.findBySlug(slug).filter(BookingPage::isActive)
                .orElseThrow(() -> ApiException.notFound("Booking page"));
    }

    private BookingPage owned(CurrentUser user, UUID id) {
        return pages.findById(id).filter(p -> p.getOwnerId().equals(user.userId()))
                .orElseThrow(() -> ApiException.notFound("Booking page"));
    }

    static BookingPageView view(BookingPage p) {
        return new BookingPageView(p.getId(), p.getSlug(), p.getTitle(), p.getDescription(), p.getDurationMinutes(),
                p.getSlotIntervalMinutes(), p.getMinNoticeMinutes(), p.getMaxAdvanceDays(), p.getBufferBeforeMinutes(),
                p.getBufferAfterMinutes(), p.getMediaMode(), p.isActive(), p.getCreatedAt(),
                p.getVersion() == null ? 0 : p.getVersion());
    }
}
