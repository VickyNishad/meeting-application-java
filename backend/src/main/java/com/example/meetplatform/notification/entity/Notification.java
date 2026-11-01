package com.example.meetplatform.notification.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Outbox row for an e-mail (delivered asynchronously with retries) or an in-app notification. */
@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

    public enum Channel { IN_APP, EMAIL }

    public enum Status { PENDING, SENT, FAILED, CANCELLED }

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "recipient_email", length = 320)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Channel channel;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(nullable = false, length = 300)
    private String title;

    private String body;

    @Column(length = 1024)
    private String link;

    @Column(name = "ics_attachment")
    private String icsAttachment;

    @Column(nullable = false)
    private boolean sensitive;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public static Notification email(UUID userId, String to, String type, String subject, String body, String ics,
                                     boolean sensitive, UUID referenceId, Instant scheduledAt, Instant now) {
        Notification n = new Notification();
        n.userId = userId;
        n.recipientEmail = to;
        n.channel = Channel.EMAIL;
        n.type = type;
        n.title = truncate(subject, 300);
        n.body = body;
        n.icsAttachment = ics;
        n.sensitive = sensitive;
        n.referenceId = referenceId;
        n.scheduledAt = scheduledAt;
        n.createdAt = now;
        return n;
    }

    public static Notification inApp(UUID userId, String type, String title, String body, String link,
                                     UUID referenceId, Instant now) {
        Notification n = new Notification();
        n.userId = userId;
        n.channel = Channel.IN_APP;
        n.type = type;
        n.title = truncate(title, 300);
        n.body = body;
        n.link = link;
        n.referenceId = referenceId;
        n.status = Status.SENT;
        n.scheduledAt = now;
        n.sentAt = now;
        n.createdAt = now;
        return n;
    }

    public void markSent(Instant now) {
        status = Status.SENT;
        sentAt = now;
        attempts++;
        if (sensitive) {
            // One-time links must not linger in the outbox once delivered.
            body = "[redacted after delivery]";
        }
    }

    public void markAttemptFailed(String error, int maxAttempts, Instant nextAttempt) {
        attempts++;
        lastError = truncate(error, 1000);
        if (attempts >= maxAttempts) {
            status = Status.FAILED;
            if (sensitive) {
                body = "[redacted]";
            }
        } else {
            scheduledAt = nextAttempt;
        }
    }

    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getLink() {
        return link;
    }

    public String getIcsAttachment() {
        return icsAttachment;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
