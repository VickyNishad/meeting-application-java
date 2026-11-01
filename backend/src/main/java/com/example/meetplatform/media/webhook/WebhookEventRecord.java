package com.example.meetplatform.media.webhook;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "webhook_events")
public class WebhookEventRecord extends BaseEntity {

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected WebhookEventRecord() {
    }

    public WebhookEventRecord(String provider, String eventId, String eventType, Instant receivedAt) {
        this.provider = provider;
        this.eventId = eventId;
        this.eventType = eventType;
        this.receivedAt = receivedAt;
    }

    public void markProcessed(Instant now) {
        this.processedAt = now;
    }
}
