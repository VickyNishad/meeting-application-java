package com.example.meetplatform.media.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEventRecord, UUID> {

    /** Atomic "claim": returns 1 for the first delivery of an event id, 0 for any duplicate. */
    @Modifying
    @Query(value = """
            INSERT INTO webhook_events (id, provider, event_id, event_type, received_at)
            VALUES (:id, :provider, :eventId, :eventType, :receivedAt)
            ON CONFLICT (provider, event_id) DO NOTHING
            """, nativeQuery = true)
    int claim(UUID id, String provider, String eventId, String eventType, Instant receivedAt);

    @Modifying
    @Query(value = "UPDATE webhook_events SET processed_at = :now WHERE provider = :provider AND event_id = :eventId",
            nativeQuery = true)
    int markProcessed(String provider, String eventId, Instant now);
}
