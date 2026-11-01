package com.example.meetplatform.notification.repository;

import com.example.meetplatform.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Claims due e-mails. SKIP LOCKED lets several API instances dispatch concurrently without sending the same
     * message twice; rows stay locked until the claiming transaction commits.
     */
    @Query(value = """
            SELECT * FROM notifications
            WHERE status = 'PENDING' AND channel = 'EMAIL' AND scheduled_at <= :now
            ORDER BY scheduled_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> claimDue(Instant now, int limit);

    @Query("""
            select n from Notification n
            where n.userId = :userId and n.channel = com.example.meetplatform.notification.entity.Notification.Channel.IN_APP
              and (:unreadOnly = false or n.readAt is null)
            order by n.createdAt desc
            """)
    Page<Notification> findInApp(UUID userId, boolean unreadOnly, Pageable pageable);

    @Modifying
    @Query("""
            update Notification n set n.readAt = :now
            where n.userId = :userId and n.channel = com.example.meetplatform.notification.entity.Notification.Channel.IN_APP
              and n.readAt is null
            """)
    int markAllRead(UUID userId, Instant now);

    @Modifying
    @Query("""
            update Notification n set n.status = com.example.meetplatform.notification.entity.Notification.Status.CANCELLED
            where n.referenceId = :referenceId and n.type = :type
              and n.status = com.example.meetplatform.notification.entity.Notification.Status.PENDING
            """)
    int cancelPending(UUID referenceId, String type);
}
