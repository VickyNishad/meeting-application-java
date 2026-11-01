package com.example.meetplatform.room.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.util.Tokens;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingStatus;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.meeting.service.MeetingAccessService;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.event.RoomEvents;
import com.example.meetplatform.room.repository.MeetingRoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Starts and ends live sessions. Callers that start or end a session must hold the meeting row lock
 * ({@link MeetingRepository#findByIdForUpdate}) so concurrent joins cannot create two rooms.
 */
@Service
public class RoomSessionService {

    private static final Logger log = LoggerFactory.getLogger(RoomSessionService.class);

    private final MeetingRoomRepository rooms;
    private final MeetingRepository meetings;
    private final MeetingAccessService access;
    private final MediaService media;
    private final RoomSignals signals;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RoomSessionService(MeetingRoomRepository rooms, MeetingRepository meetings, MeetingAccessService access,
                              MediaService media, RoomSignals signals, AuditService audit,
                              ApplicationEventPublisher events, Clock clock) {
        this.rooms = rooms;
        this.meetings = meetings;
        this.access = access;
        this.media = media;
        this.signals = signals;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<MeetingRoom> active(UUID meetingId) {
        return rooms.findFirstByMeetingIdAndStatus(meetingId, MeetingRoom.Status.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Optional<MeetingRoom> byRoomName(String roomName) {
        return rooms.findByLivekitRoomName(roomName);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MeetingRoom start(Meeting meeting, UUID startedBy) {
        String roomName = "mtg-" + meeting.getId() + "-" + Tokens.randomCode(6);
        // (Attendee audience rooms of a HOST_AND_SELF_ONLY session are created as attendees join, see RoomNames.)
        // If the transaction rolls back, do not leave the media room behind: registered before the room is created, so
        // a failure after creating it (here or later in the join) still cleans it up.
        afterRollback(() -> {
            try {
                media.deleteRoom(roomName);
            } catch (RuntimeException e) {
                log.warn("Could not delete orphan media room {}: {}", roomName, e.getMessage());
            }
        });
        // Create the media room first: if the SFU is down, nothing is persisted and the join fails cleanly.
        media.createRoom(roomName, meeting.getMaxParticipants());
        MeetingRoom room = rooms.save(new MeetingRoom(meeting.getId(), roomName, clock.instant()));
        meeting.setStatus(MeetingStatus.LIVE);
        Map<String, Object> details = new HashMap<>();
        details.put("roomId", room.getId());
        audit.record(startedBy, null, AuditActions.MEETING_STARTED, AuditActions.RESOURCE_MEETING, meeting.getId(), details);
        events.publishEvent(new RoomEvents.SessionStarted(meeting.getId(), room.getId(), roomName));
        return room;
    }

    /** Ends the active session: notifies clients, closes the media room, and settles the meeting status. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void end(Meeting meeting, String reason, UUID actorUserId) {
        Optional<MeetingRoom> active = active(meeting.getId());
        if (active.isPresent()) {
            MeetingRoom room = active.get();
            room.end(clock.instant());
            String roomName = room.getLivekitRoomName();
            signals.broadcast(roomName, RoomSignals.TOPIC_MODERATION, Map.of("type", "meeting-ended"));
            // Attendee audience rooms (HOST_AND_SELF_ONLY) are closed by the participant module on SessionEnded.
            afterCommit(() -> {
                try {
                    media.deleteRoom(roomName);
                } catch (RuntimeException e) {
                    log.warn("Could not delete media room {}: {}", roomName, e.getMessage());
                }
            });
            events.publishEvent(new RoomEvents.SessionEnded(meeting.getId(), room.getId(), roomName, reason));
        }
        if (meeting.getStatus() == MeetingStatus.LIVE) {
            meeting.setStatus(meeting.isReusable() ? MeetingStatus.SCHEDULED : MeetingStatus.ENDED);
        }
        audit.record(actorUserId, null, AuditActions.MEETING_ENDED, AuditActions.RESOURCE_MEETING, meeting.getId(),
                Map.of("reason", reason));
    }

    @Transactional
    public void observeParticipantCount(UUID roomId, int count) {
        rooms.findById(roomId).ifPresent(r -> r.observeParticipants(count));
    }

    @Transactional(readOnly = true)
    public List<SessionView> sessions(CurrentUser user, UUID meetingId) {
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        access.requireHost(meeting, user);
        return rooms.findByMeetingIdOrderByStartedAtDesc(meetingId).stream()
                .map(r -> new SessionView(r.getId(), r.getLivekitRoomName(), r.getStatus().name(), r.getStartedAt(),
                        r.getEndedAt(), r.getPeakParticipants()))
                .toList();
    }

    /** The SFU closed an empty room (empty-timeout) or it was deleted elsewhere: settle our state. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onMediaEvent(MediaWebhookEvent event) {
        if (event.type() != MediaWebhookEvent.Type.ROOM_FINISHED || event.roomName() == null) {
            return;
        }
        rooms.findByLivekitRoomName(event.roomName()).filter(MeetingRoom::isActive).ifPresent(room ->
                meetings.findByIdForUpdate(room.getMeetingId()).ifPresent(meeting -> end(meeting, "ROOM_FINISHED", null)));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static void afterRollback(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        action.run();
                    }
                }
            });
        }
    }

    public record SessionView(UUID id, String roomName, String status, java.time.Instant startedAt,
                              java.time.Instant endedAt, int peakParticipants) {
    }
}
