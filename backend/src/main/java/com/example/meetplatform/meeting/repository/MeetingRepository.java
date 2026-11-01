package com.example.meetplatform.meeting.repository;

import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    Optional<Meeting> findByJoinCode(String joinCode);

    boolean existsByJoinCode(String joinCode);

    /** Serializes joins and lifecycle changes of one meeting (capacity, room creation, admission). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Meeting m where m.id = :id")
    Optional<Meeting> findByIdForUpdate(UUID id);

    @Query("select m from Meeting m where m.hostId = :hostId and m.type = com.example.meetplatform.meeting.entity.MeetingEnums.MeetingType.PERSONAL_ROOM")
    Optional<Meeting> findPersonalRoom(UUID hostId);

    List<Meeting> findByStatus(MeetingStatus status);

    long countByStatus(MeetingStatus status);

    long countByCreatedAtGreaterThanEqual(Instant since);

    /** Meetings that block the host's calendar in [from, to). Used to compute free booking slots. */
    @Query("""
            select m from Meeting m
            where m.hostId = :hostId and m.status in (com.example.meetplatform.meeting.entity.MeetingEnums.MeetingStatus.SCHEDULED,
                                                      com.example.meetplatform.meeting.entity.MeetingEnums.MeetingStatus.LIVE)
              and m.startTime is not null and m.endTime is not null
              and m.startTime < :to and m.endTime > :from
            """)
    List<Meeting> findBusyForHost(UUID hostId, Instant from, Instant to);

    @Query(value = """
            SELECT m.* FROM meetings m
            WHERE m.type <> 'PERSONAL_ROOM'
              AND (m.host_id = :userId
                   OR EXISTS (SELECT 1 FROM meeting_invitations i WHERE i.meeting_id = m.id AND i.status <> 'CANCELLED'
                              AND (i.user_id = :userId OR lower(i.email) = :email))
                   OR EXISTS (SELECT 1 FROM meeting_participants p WHERE p.meeting_id = m.id AND p.user_id = :userId
                              AND p.admitted_at IS NOT NULL AND p.state IN ('ADMITTED', 'JOINED', 'LEFT')
                              AND NOT EXISTS (SELECT 1 FROM meeting_participants r WHERE r.meeting_id = m.id
                                  AND r.user_id = :userId AND r.state = 'REMOVED' AND r.created_at >= p.created_at)))
              AND (:scope = 'all'
                   OR (:scope = 'live' AND m.status = 'LIVE')
                   OR (:scope = 'upcoming' AND m.status IN ('SCHEDULED', 'LIVE')
                       AND (m.end_time IS NULL OR m.end_time >= :now OR m.recurrence_rule IS NOT NULL))
                   OR (:scope = 'past' AND (m.status IN ('ENDED', 'CANCELLED')
                       OR (m.status = 'SCHEDULED' AND m.end_time < :now AND m.recurrence_rule IS NULL))))
            ORDER BY CASE WHEN :scope = 'past' THEN NULL ELSE m.start_time END ASC NULLS FIRST,
                     m.start_time DESC NULLS LAST, m.created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM meetings m
            WHERE m.type <> 'PERSONAL_ROOM'
              AND (m.host_id = :userId
                   OR EXISTS (SELECT 1 FROM meeting_invitations i WHERE i.meeting_id = m.id AND i.status <> 'CANCELLED'
                              AND (i.user_id = :userId OR lower(i.email) = :email))
                   OR EXISTS (SELECT 1 FROM meeting_participants p WHERE p.meeting_id = m.id AND p.user_id = :userId
                              AND p.admitted_at IS NOT NULL AND p.state IN ('ADMITTED', 'JOINED', 'LEFT')
                              AND NOT EXISTS (SELECT 1 FROM meeting_participants r WHERE r.meeting_id = m.id
                                  AND r.user_id = :userId AND r.state = 'REMOVED' AND r.created_at >= p.created_at)))
              AND (:scope = 'all'
                   OR (:scope = 'live' AND m.status = 'LIVE')
                   OR (:scope = 'upcoming' AND m.status IN ('SCHEDULED', 'LIVE')
                       AND (m.end_time IS NULL OR m.end_time >= :now OR m.recurrence_rule IS NOT NULL))
                   OR (:scope = 'past' AND (m.status IN ('ENDED', 'CANCELLED')
                       OR (m.status = 'SCHEDULED' AND m.end_time < :now AND m.recurrence_rule IS NULL))))
            """,
            nativeQuery = true)
    Page<Meeting> findVisible(UUID userId, String email, String scope, Instant now, Pageable pageable);
}
