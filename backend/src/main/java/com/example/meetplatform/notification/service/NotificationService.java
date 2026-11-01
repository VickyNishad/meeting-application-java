package com.example.meetplatform.notification.service;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.response.PageParams;
import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.notification.entity.Notification;
import com.example.meetplatform.notification.repository.NotificationRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public void email(UUID userId, String to, String type, String subject, String body, String ics, boolean sensitive,
                      UUID referenceId) {
        emailAt(userId, to, type, subject, body, ics, sensitive, referenceId, clock.instant());
    }

    @Transactional
    public void emailAt(UUID userId, String to, String type, String subject, String body, String ics,
                        boolean sensitive, UUID referenceId, Instant sendAt) {
        notifications.save(Notification.email(userId, to, type, subject, body, ics, sensitive, referenceId, sendAt,
                clock.instant()));
    }

    @Transactional
    public void inApp(UUID userId, String type, String title, String body, String link, UUID referenceId) {
        notifications.save(Notification.inApp(userId, type, title, body, link, referenceId, clock.instant()));
    }

    @Transactional
    public int cancelPending(UUID referenceId, String type) {
        return notifications.cancelPending(referenceId, type);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> list(UUID userId, boolean unreadOnly, Integer page, Integer size) {
        return PageResponse.of(notifications.findInApp(userId, unreadOnly, PageParams.of(page, size, Sort.unsorted())),
                NotificationView::of);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        notifications.findById(notificationId).filter(n -> userId.equals(n.getUserId()))
                .orElseThrow(() -> ApiException.notFound("Notification")).markRead(clock.instant());
    }

    @Transactional
    public void markAllRead(UUID userId) {
        notifications.markAllRead(userId, clock.instant());
    }

    public record NotificationView(UUID id, String type, String title, String body, String link, boolean read,
                                   Instant createdAt) {

        static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLink(),
                    n.getReadAt() != null, n.getCreatedAt());
        }
    }
}
