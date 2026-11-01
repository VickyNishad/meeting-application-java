package com.example.meetplatform.booking.entity;

import com.example.meetplatform.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A confirmed time on a host's calendar. {@code blockedStart/blockedEnd} include the page buffers and are what
 * the PostgreSQL exclusion constraint protects against overlap.
 */
@Entity
@Table(name = "bookings")
public class Booking extends AuditedEntity {

    public enum Status { CONFIRMED, CANCELLED, RESCHEDULED }

    @Column(name = "booking_page_id", nullable = false)
    private UUID bookingPageId;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "meeting_id")
    private UUID meetingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.CONFIRMED;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "blocked_start", nullable = false)
    private Instant blockedStart;

    @Column(name = "blocked_end", nullable = false)
    private Instant blockedEnd;

    @Column(name = "invitee_name", nullable = false, length = 120)
    private String inviteeName;

    @Column(name = "invitee_email", nullable = false, length = 320)
    private String inviteeEmail;

    @Column(name = "invitee_time_zone", nullable = false, length = 64)
    private String inviteeTimeZone;

    @Column(length = 2000)
    private String notes;

    @Column(name = "manage_token_hash", nullable = false, length = 64)
    private String manageTokenHash;

    @Column(name = "rescheduled_from_id")
    private UUID rescheduledFromId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "external_calendar_provider", length = 32)
    private String externalCalendarProvider;

    @Column(name = "external_event_id", length = 512)
    private String externalEventId;

    protected Booking() {
    }

    public void linkExternalEvent(String provider, String eventId) {
        this.externalCalendarProvider = provider;
        this.externalEventId = eventId;
    }

    public String getExternalCalendarProvider() {
        return externalCalendarProvider;
    }

    public String getExternalEventId() {
        return externalEventId;
    }

    public Booking(UUID bookingPageId, UUID hostId, Instant startTime, Instant endTime, Instant blockedStart,
                   Instant blockedEnd, String inviteeName, String inviteeEmail, String inviteeTimeZone, String notes,
                   String manageTokenHash) {
        this.bookingPageId = bookingPageId;
        this.hostId = hostId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.blockedStart = blockedStart;
        this.blockedEnd = blockedEnd;
        this.inviteeName = inviteeName;
        this.inviteeEmail = inviteeEmail;
        this.inviteeTimeZone = inviteeTimeZone;
        this.notes = notes;
        this.manageTokenHash = manageTokenHash;
    }

    public boolean isModifiable(Instant now) {
        return status == Status.CONFIRMED && startTime.isAfter(now);
    }

    public void cancel(Instant now, String reason) {
        status = Status.CANCELLED;
        cancelledAt = now;
        cancelReason = reason;
    }

    public UUID getBookingPageId() {
        return bookingPageId;
    }

    public UUID getHostId() {
        return hostId;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(UUID meetingId) {
        this.meetingId = meetingId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public Instant getBlockedStart() {
        return blockedStart;
    }

    public Instant getBlockedEnd() {
        return blockedEnd;
    }

    public String getInviteeName() {
        return inviteeName;
    }

    public String getInviteeEmail() {
        return inviteeEmail;
    }

    public String getInviteeTimeZone() {
        return inviteeTimeZone;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getRescheduledFromId() {
        return rescheduledFromId;
    }

    public void setRescheduledFromId(UUID rescheduledFromId) {
        this.rescheduledFromId = rescheduledFromId;
    }

    public String getCancelReason() {
        return cancelReason;
    }
}
