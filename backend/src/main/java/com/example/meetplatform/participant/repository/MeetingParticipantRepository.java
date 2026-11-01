package com.example.meetplatform.participant.repository;

import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.entity.MeetingParticipant.State;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, UUID> {

    Optional<MeetingParticipant> findByIdAndMeetingId(UUID id, UUID meetingId);

    Optional<MeetingParticipant> findByMeetingIdAndWaitTokenHash(UUID meetingId, String waitTokenHash);

    Optional<MeetingParticipant> findFirstByMeetingIdAndUserIdOrderByCreatedAtDesc(UUID meetingId, UUID userId);

    /**
     * The user was admitted to a session of the meeting and has not been removed since (see
     * {@link MeetingParticipant#wasAdmitted()}); a later REMOVED participation revokes earlier admissions.
     */
    @Query("""
            select case when count(p) > 0 then true else false end from MeetingParticipant p
            where p.meetingId = :meetingId and p.userId = :userId and p.admittedAt is not null
              and p.state in (com.example.meetplatform.participant.entity.MeetingParticipant.State.ADMITTED,
                              com.example.meetplatform.participant.entity.MeetingParticipant.State.JOINED,
                              com.example.meetplatform.participant.entity.MeetingParticipant.State.LEFT)
              and not exists (select r.id from MeetingParticipant r
                              where r.meetingId = p.meetingId and r.userId = p.userId
                                and r.state = com.example.meetplatform.participant.entity.MeetingParticipant.State.REMOVED
                                and r.createdAt >= p.createdAt)
            """)
    boolean wasAdmitted(UUID meetingId, UUID userId);

    /** The user's most recent participation in one live session (room). */
    Optional<MeetingParticipant> findFirstByRoomIdAndUserIdOrderByCreatedAtDesc(UUID roomId, UUID userId);

    List<MeetingParticipant> findByRoomIdAndStateIn(UUID roomId, Collection<State> states);

    List<MeetingParticipant> findByMeetingIdAndStateOrderByCreatedAtAsc(UUID meetingId, State state);

    List<MeetingParticipant> findByMeetingIdOrderByCreatedAtAsc(UUID meetingId);

    @Query("select p from MeetingParticipant p where p.meetingId = :meetingId and p.roomId is null and p.state = :state"
            + " order by p.createdAt asc")
    List<MeetingParticipant> findWithoutRoom(UUID meetingId, State state);

    /**
     * Seats taken in a session. An ADMITTED participant that never connected stops holding a seat after the
     * grace period, so abandoned tabs cannot exhaust capacity.
     */
    @Query("""
            select count(p) from MeetingParticipant p
            where p.roomId = :roomId
              and (p.state = com.example.meetplatform.participant.entity.MeetingParticipant.State.JOINED
                   or (p.state = com.example.meetplatform.participant.entity.MeetingParticipant.State.ADMITTED
                       and p.admittedAt > :admittedAfter))
            """)
    long countOccupiedSeats(UUID roomId, Instant admittedAfter);

    /** People with a confirmed media connection, once per person (signed-in users by account, guests by participation). */
    @Query("""
            select count(distinct coalesce(p.userId, p.id)) from MeetingParticipant p
            where p.roomId = :roomId and p.state = :state and p.role in :roles
            """)
    long countPeople(UUID roomId, State state, Collection<MeetingRole> roles);

    long countByState(State state);

    @Query("select count(p) from MeetingParticipant p where p.roomId = :roomId and p.state = :state")
    long countByRoomIdAndState(UUID roomId, State state);
}
