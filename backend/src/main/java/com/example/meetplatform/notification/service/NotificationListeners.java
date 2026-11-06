package com.example.meetplatform.notification.service;

import com.example.meetplatform.booking.entity.Booking;
import com.example.meetplatform.booking.event.BookingEvents;
import com.example.meetplatform.booking.service.BookingService;
import com.example.meetplatform.calendar.service.IcsBuilder;
import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.event.IdentityEvents;
import com.example.meetplatform.identity.service.UserService;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingInvitation;
import com.example.meetplatform.meeting.event.MeetingEvents;
import com.example.meetplatform.meeting.mapper.MeetingMapper;
import com.example.meetplatform.meeting.repository.MeetingInvitationRepository;
import com.example.meetplatform.meeting.service.MeetingService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Turns domain events into e-mails, calendar invitations and in-app notifications. Listeners run inside the
 * publishing transaction, so messages are only queued when the business change commits.
 */
@Component
public class NotificationListeners {

    static final String TYPE_VERIFY = "EMAIL_VERIFICATION";
    static final String TYPE_RESET = "PASSWORD_RESET";
    static final String TYPE_PASSWORD_CHANGED = "PASSWORD_CHANGED";
    static final String TYPE_INVITATION = "MEETING_INVITATION";
    static final String TYPE_MEETING_UPDATED = "MEETING_UPDATED";
    static final String TYPE_MEETING_CANCELLED = "MEETING_CANCELLED";
    static final String TYPE_BOOKING_CONFIRMED = "BOOKING_CONFIRMED";
    static final String TYPE_BOOKING_CANCELLED = "BOOKING_CANCELLED";
    static final String TYPE_BOOKING_REMINDER = "BOOKING_REMINDER";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
            .withLocale(Locale.ENGLISH);

    private final NotificationService notifications;
    private final MeetingService meetings;
    private final MeetingInvitationRepository invitations;
    private final MeetingMapper meetingMapper;
    private final BookingService bookings;
    private final UserService users;
    private final AppProperties properties;
    private final Clock clock;

    public NotificationListeners(NotificationService notifications, MeetingService meetings,
                                 MeetingInvitationRepository invitations, MeetingMapper meetingMapper,
                                 BookingService bookings, UserService users, AppProperties properties, Clock clock) {
        this.notifications = notifications;
        this.meetings = meetings;
        this.invitations = invitations;
        this.meetingMapper = meetingMapper;
        this.bookings = bookings;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ identity

    @EventListener
    public void onVerification(IdentityEvents.EmailVerificationRequested e) {
        String link = properties.publicUrl() + "/verify-email?token=" + encode(e.rawToken());
        notifications.emailWithLink(e.userId(), e.email(), TYPE_VERIFY, "Verify your e-mail address",
                "Hi " + e.displayName() + ",\n\nConfirm your e-mail address to finish creating your account:\n" + link
                        + "\n\nThis link expires in " + properties.auth().emailVerificationTtl().toHours()
                        + " hours. If you did not sign up, ignore this message.", link, true, e.userId());
    }

    @EventListener
    public void onPasswordReset(IdentityEvents.PasswordResetRequested e) {
        String link = properties.publicUrl() + "/reset-password?token=" + encode(e.rawToken());
        notifications.emailWithLink(e.userId(), e.email(), TYPE_RESET, "Reset your password",
                "Hi " + e.displayName() + ",\n\nUse this link to choose a new password:\n" + link
                        + "\n\nIt expires in " + properties.auth().passwordResetTtl().toMinutes()
                        + " minutes. If you did not ask for this, you can ignore it; your password is unchanged.",
                link, true, e.userId());
    }

    @EventListener
    public void onPasswordChanged(IdentityEvents.PasswordChanged e) {
        notifications.email(e.userId(), e.email(), TYPE_PASSWORD_CHANGED, "Your password was changed",
                "Hi " + e.displayName() + ",\n\nYour password was just changed and your other sessions were signed out."
                        + "\nIf this was not you, reset your password immediately.", null, false, e.userId());
    }

    // ------------------------------------------------------------------ meetings

    @EventListener
    public void onInvitations(MeetingEvents.InvitationsCreated e) {
        Meeting meeting = meetings.require(e.meetingId());
        User host = users.require(meeting.getHostId());
        Instant now = clock.instant();
        for (MeetingInvitation invitation : invitations.findAllById(e.invitationIds())) {
            String ics = meeting.getStartTime() == null ? null
                    : IcsBuilder.build(IcsBuilder.Method.REQUEST, event(meeting, host, List.of(invitation.getEmail())), now);
            notifications.email(invitation.getUserId(), invitation.getEmail(), TYPE_INVITATION,
                    "Invitation: " + meeting.getTitle(),
                    host.getDisplayName() + " invited you to \"" + meeting.getTitle() + "\".\n\n"
                            + whenLine(meeting, zoneFor(invitation.getUserId(), meeting.getTimeZone()))
                            + "Join: " + meetingMapper.joinUrl(meeting) + "\n", ics, false, meeting.getId());
            if (invitation.getUserId() != null) {
                notifications.inApp(invitation.getUserId(), TYPE_INVITATION, "Invitation: " + meeting.getTitle(),
                        "From " + host.getDisplayName(), "/meetings/" + meeting.getId(), meeting.getId());
            }
            invitation.markSent(now);
        }
    }

    @EventListener
    public void onRescheduled(MeetingEvents.MeetingRescheduled e) {
        notifyInvitees(e.meetingId(), IcsBuilder.Method.REQUEST, TYPE_MEETING_UPDATED, "Updated: ", "was updated");
    }

    @EventListener
    public void onCancelled(MeetingEvents.MeetingCancelled e) {
        notifyInvitees(e.meetingId(), IcsBuilder.Method.CANCEL, TYPE_MEETING_CANCELLED, "Cancelled: ", "was cancelled");
    }

    private void notifyInvitees(UUID meetingId, IcsBuilder.Method method, String type, String prefix, String verb) {
        Meeting meeting = meetings.require(meetingId);
        User host = users.require(meeting.getHostId());
        List<MeetingInvitation> list = invitations.findByMeetingIdOrderByCreatedAtAsc(meetingId).stream()
                .filter(i -> i.getStatus() != MeetingInvitation.Status.CANCELLED || method == IcsBuilder.Method.CANCEL)
                .toList();
        Instant now = clock.instant();
        for (MeetingInvitation invitation : list) {
            String ics = meeting.getStartTime() == null ? null
                    : IcsBuilder.build(method, event(meeting, host, List.of(invitation.getEmail())), now);
            notifications.email(invitation.getUserId(), invitation.getEmail(), type, prefix + meeting.getTitle(),
                    "\"" + meeting.getTitle() + "\" " + verb + " by " + host.getDisplayName() + ".\n\n"
                            + (method == IcsBuilder.Method.REQUEST
                            ? whenLine(meeting, zoneFor(invitation.getUserId(), meeting.getTimeZone()))
                            + "Join: " + meetingMapper.joinUrl(meeting) + "\n" : ""), ics, false, meeting.getId());
            if (invitation.getUserId() != null) {
                notifications.inApp(invitation.getUserId(), type, prefix + meeting.getTitle(), null,
                        "/meetings/" + meeting.getId(), meeting.getId());
            }
        }
    }

    // ------------------------------------------------------------------ bookings

    @EventListener
    public void onBookingConfirmed(BookingEvents.BookingConfirmed e) {
        Booking booking = bookings.require(e.bookingId());
        sendBookingConfirmation(booking, "Confirmed: ");
        scheduleReminders(booking);
    }

    @EventListener
    public void onBookingRescheduled(BookingEvents.BookingRescheduled e) {
        notifications.cancelPending(e.previousBookingId(), TYPE_BOOKING_REMINDER);
        Booking booking = bookings.require(e.bookingId());
        sendBookingConfirmation(booking, "Rescheduled: ");
        scheduleReminders(booking);
    }

    @EventListener
    public void onBookingCancelled(BookingEvents.BookingCancelled e) {
        notifications.cancelPending(e.bookingId(), TYPE_BOOKING_REMINDER);
        Booking booking = bookings.require(e.bookingId());
        Meeting meeting = meetings.require(booking.getMeetingId());
        User host = users.require(booking.getHostId());
        String ics = IcsBuilder.build(IcsBuilder.Method.CANCEL, event(meeting, host, List.of(booking.getInviteeEmail())),
                clock.instant());
        String who = e.byHost() ? host.getDisplayName() : booking.getInviteeName();
        String body = "\"" + meeting.getTitle() + "\" on " + format(booking.getStartTime(), booking.getInviteeTimeZone())
                + " was cancelled by " + who + "."
                + (booking.getCancelReason() == null ? "" : "\nReason: " + booking.getCancelReason());
        notifications.email(null, booking.getInviteeEmail(), TYPE_BOOKING_CANCELLED, "Cancelled: " + meeting.getTitle(),
                body, ics, false, booking.getId());
        notifications.email(host.getId(), host.getEmail(), TYPE_BOOKING_CANCELLED, "Cancelled: " + meeting.getTitle(),
                body, ics, false, booking.getId());
        notifications.inApp(host.getId(), TYPE_BOOKING_CANCELLED, "Booking cancelled: " + booking.getInviteeName(),
                null, "/scheduling/bookings", booking.getId());
    }

    private void sendBookingConfirmation(Booking booking, String prefix) {
        Meeting meeting = meetings.require(booking.getMeetingId());
        User host = users.require(booking.getHostId());
        String ics = IcsBuilder.build(IcsBuilder.Method.REQUEST, event(meeting, host, List.of(booking.getInviteeEmail())),
                clock.instant());
        String join = meetingMapper.joinUrl(meeting);
        notifications.email(null, booking.getInviteeEmail(), TYPE_BOOKING_CONFIRMED, prefix + meeting.getTitle(),
                "Hi " + booking.getInviteeName() + ",\n\nYour meeting with " + host.getDisplayName() + " is booked for "
                        + format(booking.getStartTime(), booking.getInviteeTimeZone()) + ".\nJoin: " + join
                        + "\n\nTo cancel or reschedule, use the link on your confirmation page.", ics, false, booking.getId());
        notifications.email(host.getId(), host.getEmail(), TYPE_BOOKING_CONFIRMED,
                prefix + meeting.getTitle(),
                booking.getInviteeName() + " <" + booking.getInviteeEmail() + "> booked "
                        + format(booking.getStartTime(), host.getTimeZone()) + ".\nJoin: " + join
                        + (booking.getNotes() == null ? "" : "\nNotes: " + booking.getNotes()), ics, false, booking.getId());
        notifications.inApp(host.getId(), TYPE_BOOKING_CONFIRMED, "New booking: " + booking.getInviteeName(),
                format(booking.getStartTime(), host.getTimeZone()), "/meetings/" + meeting.getId(), booking.getId());
    }

    private void scheduleReminders(Booking booking) {
        Meeting meeting = meetings.require(booking.getMeetingId());
        User host = users.require(booking.getHostId());
        Instant now = clock.instant();
        for (Duration offset : properties.notifications().reminderOffsets()) {
            Instant at = booking.getStartTime().minus(offset);
            if (!at.isAfter(now)) {
                continue;
            }
            String join = meetingMapper.joinUrl(meeting);
            notifications.emailAt(null, booking.getInviteeEmail(), TYPE_BOOKING_REMINDER, "Reminder: " + meeting.getTitle(),
                    "Your meeting with " + host.getDisplayName() + " starts "
                            + format(booking.getStartTime(), booking.getInviteeTimeZone()) + ".\nJoin: " + join,
                    null, false, booking.getId(), at);
            notifications.emailAt(host.getId(), host.getEmail(), TYPE_BOOKING_REMINDER, "Reminder: " + meeting.getTitle(),
                    "Your meeting with " + booking.getInviteeName() + " starts "
                            + format(booking.getStartTime(), host.getTimeZone()) + ".\nJoin: " + join,
                    null, false, booking.getId(), at);
        }
    }

    // ------------------------------------------------------------------ helpers

    private IcsBuilder.Event event(Meeting meeting, User host, List<String> attendees) {
        return new IcsBuilder.Event(meeting.getId(), meeting.getVersion() == null ? 0 : meeting.getVersion(),
                meeting.getStartTime(), meeting.getEndTime(), meeting.getTitle(),
                (meeting.getDescription() == null ? "" : meeting.getDescription() + "\n\n") + "Join: "
                        + meetingMapper.joinUrl(meeting),
                meetingMapper.joinUrl(meeting), host.getDisplayName(), host.getEmail(), attendees,
                meeting.getRecurrenceRule());
    }

    private String whenLine(Meeting meeting, String zone) {
        return meeting.getStartTime() == null ? "" : "When: " + format(meeting.getStartTime(), zone) + "\n";
    }

    private String zoneFor(UUID userId, String fallback) {
        return userId == null ? fallback : users.require(userId).getTimeZone();
    }

    private static String format(Instant instant, String zone) {
        String id = zone == null || !Set.copyOf(ZoneId.getAvailableZoneIds()).contains(zone) ? "UTC" : zone;
        return WHEN.format(instant.atZone(ZoneId.of(id))) + " (" + id + ")";
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
