package com.example.meetplatform.recording.repository;

import com.example.meetplatform.recording.entity.SpeechRecording;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpeechRecordingRepository extends JpaRepository<SpeechRecording, UUID>, JpaSpecificationExecutor<SpeechRecording> {

    /** Serializes chunk appends and the finish of one clip (one writer per file). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SpeechRecording r where r.id = :id")
    Optional<SpeechRecording> findByIdForUpdate(UUID id);

    /** Clips still being recorded whose last chunk is older than {@code before} (client gone, or a server restart). */
    @Query("""
            select r from SpeechRecording r
            where r.status = com.example.meetplatform.recording.entity.SpeechRecording.Status.RECORDING
              and r.lastChunkAt < :before
            order by r.lastChunkAt
            """)
    List<SpeechRecording> findStale(Instant before);

    /** Ids only, so the clip itself is loaded fresh under {@link #findByIdForUpdate}. */
    @Query("""
            select r.id from SpeechRecording r
            where r.participantId = :participantId
              and r.status = com.example.meetplatform.recording.entity.SpeechRecording.Status.RECORDING
            """)
    List<UUID> openClipIds(UUID participantId);

    // Filter values, per viewer: meetings the user hosts ("hosted") or clips of the user's own participations ("mine").

    // The filter follows Date -> Meeting -> Participant, so meetings and participants come with the folder dates
    // (DD-MM-YYYY, the first segment of the storage path: the clip's start in the meeting's time zone) they have clips on.

    /** Meetings with clips: id, title, folder date, and the latest clip under that title (to pick a renamed meeting's title). */
    @Query("""
            select r.meetingId, r.meetingTitle, substring(r.storagePath, 1, 10), max(r.startTime)
            from SpeechRecording r where r.hostId = :user
            group by r.meetingId, r.meetingTitle, substring(r.storagePath, 1, 10)
            """)
    List<Object[]> hostedMeetings(UUID user);

    /**
     * Participants with clips, each with the meeting it belongs to: participantId is a meeting_participants row,
     * i.e. one person's participation in one meeting. Also the folder date, and whether the participant is the
     * meeting's host (their account is the meeting's host_id, recorded on the clip; guests have no account).
     */
    @Query("""
            select distinct r.participantId, r.participantName, r.meetingId, substring(r.storagePath, 1, 10),
                   case when r.participantUserId = r.hostId then true else false end
            from SpeechRecording r where r.hostId = :user
            """)
    List<Object[]> hostedParticipants(UUID user);

    /** The folder date (DD-MM-YYYY, the first segment of the storage path). */
    @Query("select distinct substring(r.storagePath, 1, 10) from SpeechRecording r where r.hostId = :user")
    List<String> hostedDates(UUID user);

    @Query("""
            select r.meetingId, r.meetingTitle, substring(r.storagePath, 1, 10), max(r.startTime)
            from SpeechRecording r where r.participantUserId = :user
            group by r.meetingId, r.meetingTitle, substring(r.storagePath, 1, 10)
            """)
    List<Object[]> ownMeetings(UUID user);

    @Query("""
            select distinct r.participantId, r.participantName, r.meetingId, substring(r.storagePath, 1, 10),
                   case when r.participantUserId = r.hostId then true else false end
            from SpeechRecording r where r.participantUserId = :user
            """)
    List<Object[]> ownParticipants(UUID user);

    @Query("select distinct substring(r.storagePath, 1, 10) from SpeechRecording r where r.participantUserId = :user")
    List<String> ownDates(UUID user);
}
