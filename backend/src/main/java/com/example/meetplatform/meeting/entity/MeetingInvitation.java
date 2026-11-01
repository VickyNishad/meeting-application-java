package com.example.meetplatform.meeting.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meeting_invitations")
public class MeetingInvitation extends BaseEntity {

    public enum Status { PENDING, SENT, ACCEPTED, DECLINED, CANCELLED }

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MeetingInvitation() {
    }

    public MeetingInvitation(UUID meetingId, String email, UUID userId, Instant now) {
        this.meetingId = meetingId;
        this.email = email;
        this.userId = userId;
        this.createdAt = now;
    }

    public void markSent(Instant now) {
        if (status == Status.PENDING) {
            status = Status.SENT;
        }
        sentAt = now;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public String getEmail() {
        return email;
    }

    public UUID getUserId() {
        return userId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
