package com.example.meetplatform.chat.repository;

import com.example.meetplatform.chat.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByMeetingIdAndCreatedAtBeforeOrderByCreatedAtDesc(UUID meetingId, Instant before, Pageable page);

    /** A webinar attendee's view: messages for everyone, plus the attendee's own stage-only messages. */
    @Query("""
            select m from ChatMessage m where m.meetingId = :meetingId and m.createdAt < :before
              and (m.audience = 'ALL' or m.participantId in :ownParticipantIds)
            order by m.createdAt desc
            """)
    List<ChatMessage> findVisibleToAttendee(UUID meetingId, Instant before, Collection<UUID> ownParticipantIds,
                                            Pageable page);
}
