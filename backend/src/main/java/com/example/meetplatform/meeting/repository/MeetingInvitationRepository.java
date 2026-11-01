package com.example.meetplatform.meeting.repository;

import com.example.meetplatform.meeting.entity.MeetingInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MeetingInvitationRepository extends JpaRepository<MeetingInvitation, UUID> {

    List<MeetingInvitation> findByMeetingIdOrderByCreatedAtAsc(UUID meetingId);

    @Query("""
            select count(i) > 0 from MeetingInvitation i
            where i.meetingId = :meetingId and i.status <> com.example.meetplatform.meeting.entity.MeetingInvitation.Status.CANCELLED
              and (i.userId = :userId or lower(i.email) = lower(:email))
            """)
    boolean isInvited(UUID meetingId, UUID userId, String email);

    @Query("select lower(i.email) from MeetingInvitation i where i.meetingId = :meetingId")
    List<String> findEmails(UUID meetingId);
}
