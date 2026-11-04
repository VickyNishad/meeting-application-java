package com.example.meetplatform.recording.repository;

import com.example.meetplatform.recording.entity.Recording;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordingRepository extends JpaRepository<Recording, UUID> {

    List<Recording> findByMeetingIdAndStatusNotOrderByCreatedAtDesc(UUID meetingId, Recording.Status status);

    Optional<Recording> findByEgressId(String egressId);

    List<Recording> findByRoomIdAndStatusIn(UUID roomId, Collection<Recording.Status> statuses);

    List<Recording> findByRoomIdAndKindAndStatusIn(UUID roomId, String kind, Collection<Recording.Status> statuses);

    List<Recording> findByParticipantIdAndStatusIn(UUID participantId, Collection<Recording.Status> statuses);

    List<Recording> findByMeetingIdAndAutoStartedTrueAndStatusIn(UUID meetingId, Collection<Recording.Status> statuses);

    boolean existsByMeetingIdAndStatusIn(UUID meetingId, Collection<Recording.Status> statuses);

    boolean existsByRoomIdAndKindAndAutoSuppressedTrue(UUID roomId, String kind);

    /** An automatic recording of this room failed recently (it is not retried on every bit of speech). */
    boolean existsByRoomIdAndKindAndAutoStartedTrueAndStatusAndCreatedAtAfter(UUID roomId, String kind,
                                                                             Recording.Status status, Instant after);

    boolean existsByParticipantIdAndAutoSuppressedTrue(UUID participantId);

    /** Auto-record was switched on again: recordings a moderator stopped may be started again. */
    @Modifying
    @Query("update Recording r set r.autoSuppressed = false where r.meetingId = :meetingId and r.autoSuppressed = true")
    int clearAutoSuppression(UUID meetingId);

    /**
     * Recordings a user may see: everything of meetings they host or administer; their own participant recordings;
     * and moderator-started room recordings of sessions they were admitted to. Auto-started room recordings and
     * other people's participant recordings stay with the host.
     */
    @Query(value = """
            SELECT r.* FROM recordings r JOIN meetings m ON m.id = r.meeting_id
            WHERE r.status <> 'DELETED'
              AND (m.host_id = :userId
                   OR (r.kind = 'PARTICIPANT' AND r.subject_user_id = :userId)
                   OR (r.kind = 'CLOUD' AND NOT r.auto_started
                       AND EXISTS (SELECT 1 FROM meeting_participants p WHERE p.meeting_id = m.id AND p.user_id = :userId
                              AND p.admitted_at IS NOT NULL AND p.state IN ('ADMITTED', 'JOINED', 'LEFT')
                              AND NOT EXISTS (SELECT 1 FROM meeting_participants r WHERE r.meeting_id = m.id
                                  AND r.user_id = :userId AND r.state = 'REMOVED' AND r.created_at >= p.created_at)))
                   OR EXISTS (SELECT 1 FROM organization_members om WHERE om.organization_id = m.organization_id
                              AND om.user_id = :userId AND om.role = 'ORGANIZATION_ADMIN'))
            ORDER BY r.created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM recordings r JOIN meetings m ON m.id = r.meeting_id
            WHERE r.status <> 'DELETED'
              AND (m.host_id = :userId
                   OR (r.kind = 'PARTICIPANT' AND r.subject_user_id = :userId)
                   OR (r.kind = 'CLOUD' AND NOT r.auto_started
                       AND EXISTS (SELECT 1 FROM meeting_participants p WHERE p.meeting_id = m.id AND p.user_id = :userId
                              AND p.admitted_at IS NOT NULL AND p.state IN ('ADMITTED', 'JOINED', 'LEFT')
                              AND NOT EXISTS (SELECT 1 FROM meeting_participants r WHERE r.meeting_id = m.id
                                  AND r.user_id = :userId AND r.state = 'REMOVED' AND r.created_at >= p.created_at)))
                   OR EXISTS (SELECT 1 FROM organization_members om WHERE om.organization_id = m.organization_id
                              AND om.user_id = :userId AND om.role = 'ORGANIZATION_ADMIN'))
            """,
            nativeQuery = true)
    Page<Recording> findLibrary(UUID userId, Pageable pageable);

    /** Completed recordings past retention, and failed ones (whose partial file, if any, must not linger). */
    @Query("""
            select r from Recording r where r.retentionUntil < :now
              and r.status in (com.example.meetplatform.recording.entity.Recording.Status.AVAILABLE,
                               com.example.meetplatform.recording.entity.Recording.Status.FAILED)
            order by r.retentionUntil asc
            """)
    List<Recording> findExpired(Instant now, Pageable pageable);

    /** In-flight recordings whose last sign of life is older than {@code before}: candidates for reconciliation. */
    @Query("""
            select r from Recording r where r.status in (
                com.example.meetplatform.recording.entity.Recording.Status.STARTING,
                com.example.meetplatform.recording.entity.Recording.Status.ACTIVE,
                com.example.meetplatform.recording.entity.Recording.Status.STOPPING)
              and r.updatedAt < :before
            order by r.updatedAt asc
            """)
    List<Recording> findStale(Instant before, Pageable pageable);

    @Query("select coalesce(sum(r.sizeBytes), 0) from Recording r where r.status = com.example.meetplatform.recording.entity.Recording.Status.AVAILABLE")
    long totalStoredBytes();
}
