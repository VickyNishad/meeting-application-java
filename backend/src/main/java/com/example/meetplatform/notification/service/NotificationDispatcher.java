package com.example.meetplatform.notification.service;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.notification.entity.Notification;
import com.example.meetplatform.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Background e-mail delivery from the outbox with exponential back-off. Safe to run on every instance. */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final int BATCH = 20;

    private final NotificationRepository notifications;
    private final EmailSender sender;
    private final TransactionTemplate tx;
    private final AppProperties.Notifications props;
    private final Clock clock;
    private final Counter sent;
    private final Counter failed;

    public NotificationDispatcher(NotificationRepository notifications, EmailSender sender, TransactionTemplate tx,
                                  AppProperties properties, Clock clock, MeterRegistry meters) {
        this.notifications = notifications;
        this.sender = sender;
        this.tx = tx;
        this.props = properties.notifications();
        this.clock = clock;
        this.sent = meters.counter("meet.notifications.email", "result", "sent");
        this.failed = meters.counter("meet.notifications.email", "result", "failed_attempt");
    }

    @Scheduled(fixedDelayString = "${app.notifications.dispatch-interval:5s}")
    public void dispatch() {
        if (!props.dispatchEnabled()) {
            return;
        }
        int processed;
        do {
            processed = tx.execute(status -> {
                Instant now = clock.instant();
                List<Notification> due = notifications.claimDue(now, BATCH);
                for (Notification n : due) {
                    try {
                        sender.send(n);
                        n.markSent(now);
                        sent.increment();
                    } catch (Exception e) {
                        Duration backoff = Duration.ofMinutes((long) Math.pow(2, Math.min(n.getAttempts(), 6)));
                        n.markAttemptFailed(e.getMessage(), props.maxAttempts(), now.plus(backoff));
                        failed.increment();
                        log.warn("E-mail {} ({}) attempt {} failed: {}", n.getId(), n.getType(), n.getAttempts(),
                                e.getMessage());
                    }
                }
                return due.size();
            });
        } while (processed == BATCH);
    }
}
