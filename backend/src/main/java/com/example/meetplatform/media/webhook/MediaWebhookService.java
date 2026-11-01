package com.example.meetplatform.media.webhook;

import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.media.service.MediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Verifies media-server webhooks and fans them out as application events, exactly once per event id.
 * Listeners (participants, rooms, recordings) run inside this transaction, so a failure rolls back the
 * idempotency claim too and the provider's retry is processed normally.
 */
@Service
public class MediaWebhookService {

    static final String PROVIDER = "livekit";
    private static final Logger log = LoggerFactory.getLogger(MediaWebhookService.class);

    private final MediaService media;
    private final WebhookEventRepository events;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public MediaWebhookService(MediaService media, WebhookEventRepository events, ApplicationEventPublisher publisher,
                               Clock clock) {
        this.media = media;
        this.events = events;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** @return false when the event was a duplicate and was skipped */
    @Transactional
    public boolean handle(String body, String authorization) {
        MediaWebhookEvent event = media.parseWebhook(body, authorization);
        int claimed = events.claim(UUID.randomUUID(), PROVIDER, event.id(), truncate(event.rawType()), clock.instant());
        if (claimed == 0) {
            log.debug("Duplicate webhook {} ({}) ignored", event.id(), event.rawType());
            return false;
        }
        publisher.publishEvent(event);
        events.markProcessed(PROVIDER, event.id(), clock.instant());
        return true;
    }

    private static String truncate(String s) {
        return s == null ? "unknown" : s.length() > 64 ? s.substring(0, 64) : s;
    }
}
