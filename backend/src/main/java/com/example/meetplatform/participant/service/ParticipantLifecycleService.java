package com.example.meetplatform.participant.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.persistence.AfterCommit;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.media.service.RoomNames;
import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.meeting.service.MeetingAccessService;
import com.example.meetplatform.participant.dto.ParticipantDtos.ParticipantView;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.event.RoomEvents;
import com.example.meetplatform.room.service.RoomSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Leaving, ending, presence tracking from media webhooks, and participation lists. */
@Service
public class ParticipantLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ParticipantLifecycleService.class);

    private final MeetingRepository meetings;
    private final MeetingParticipantRepository participants;
    private final ParticipantAccessService participantAccess;
    private final MeetingAccessService meetingAccess;
    private final RoomSessionService roomSessions;
    private final ParticipantPolicy policy;
    private final MediaService media;
    private final RoomSignals signals;
    private final RoomAudience audience;
    private final AuditService audit;
    private final TaskScheduler scheduler;
    private final TransactionTemplate newTx;
    private final Duration hostDropoutGrace;
    private final Duration reconnectWindow;
    private final Clock clock;
    private final MediaGrantSync grants;

    public ParticipantLifecycleService(MeetingRepository meetings, MeetingParticipantRepository participants,
                                       ParticipantAccessService participantAccess,
                                       MeetingAccessService meetingAccess,
                                       RoomSessionService roomSessions, ParticipantPolicy policy, MediaService media,
                                       RoomSignals signals, RoomAudience audience, AuditService audit,
                                       TaskScheduler scheduler, PlatformTransactionManager transactions,
                                       @Value("${app.meetings.host-dropout-grace:PT20S}") Duration hostDropoutGrace,
                                       @Value("${app.meetings.reconnect-window:PT30S}") Duration reconnectWindow,
                                       Clock clock, MediaGrantSync grants) {
        this.grants = grants;
        this.reconnectWindow = reconnectWindow;
        this.scheduler = scheduler;
        // Runs after commit or on a scheduler thread: always in a transaction of its own.
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.hostDropoutGrace = hostDropoutGrace;
        this.meetings = meetings;
        this.participants = participants;
        this.participantAccess = participantAccess;
        this.meetingAccess = meetingAccess;
        this.roomSessions = roomSessions;
        this.policy = policy;
        this.media = media;
        this.signals = signals;
        this.audience = audience;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void leave(UUID meetingId, UUID participantId, String token, Optional<CurrentUser> user) {
        Meeting meeting = meetings.findByIdForUpdate(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        MeetingParticipant participant = participantAccess.resolve(meetingId, participantId, token, user);
        if (!participant.isOpen()) {
            return;
        }
        boolean wasRecording = participant.isRecordingLocally();
        boolean handRaised = participant.isHandRaised();
        participant.markLeft(clock.instant());
        if (participant.getRoomId() == null) {
            return;
        }
        roomSessions.active(meetingId).filter(r -> r.getId().equals(participant.getRoomId())).ifPresent(room -> {
            audience.announceDeparture(meeting, room, participant, wasRecording, handRaised);
            participants.flush();
            audience.announcePresence(meeting, room, participant.getRole().isModerator());
            // In a webinar nobody is promoted automatically: that would show an attendee's identity to everyone.
            if (participant.getRole() == MeetingRole.HOST && !meeting.isStageOnly()) {
                ensureModeratorRemains(meeting, room);
            }
        });
    }

    /** Host, or a co-host while the host is absent, ends the session for everyone. */
    @Transactional
    public void end(UUID meetingId, CurrentUser user) {
        Meeting meeting = meetings.findByIdForUpdate(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        MeetingRoom room = roomSessions.active(meetingId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEETING_NOT_ACTIVE, "The meeting is not live."));
        if (!meetingAccess.isHost(meeting, user.userId())) {
            List<MeetingParticipant> present = participants.findByRoomIdAndStateIn(room.getId(), MeetingParticipant.PRESENT);
            boolean callerIsCoHost = present.stream().anyMatch(p -> user.userId().equals(p.getUserId())
                    && p.getRole() == MeetingRole.CO_HOST);
            boolean hostPresent = present.stream().anyMatch(p -> p.getRole() == MeetingRole.HOST);
            if (!callerIsCoHost || hostPresent) {
                meetingAccess.requireView(meeting, user);
                throw ApiException.forbidden("Only the host (or a co-host when the host is away) can end the meeting.");
            }
        }
        roomSessions.end(meeting, "ENDED_BY_" + (meetingAccess.isHost(meeting, user.userId()) ? "HOST" : "CO_HOST"),
                user.userId());
    }

    /**
     * Everyone who took part. Webinar attendees (participations made while the meeting was a webinar) are shown only
     * to the stage (the host, and a co-host present now) and to themselves, even if the meeting is no longer a
     * webinar; everything else is shown to every viewer.
     */
    @Transactional(readOnly = true)
    public List<ParticipantView> list(UUID meetingId, CurrentUser user) {
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        meetingAccess.requireView(meeting, user);
        List<MeetingParticipant> all = participants.findByMeetingIdOrderByCreatedAtAsc(meetingId);
        if (!isStage(meeting, user)) {
            all = all.stream()
                    .filter(p -> !p.isMasked() || user.userId().equals(p.getUserId()))
                    .toList();
        }
        return all.stream().map(ParticipantView::of).toList();
    }

    /** The host, or a co-host present in the live session. */
    private boolean isStage(Meeting meeting, CurrentUser user) {
        if (meetingAccess.isHost(meeting, user.userId())) {
            return true;
        }
        return roomSessions.active(meeting.getId())
                .map(room -> participants.findByRoomIdAndStateIn(room.getId(), MeetingParticipant.PRESENT).stream()
                        .anyMatch(p -> user.userId().equals(p.getUserId()) && p.getRole().isModerator()))
                .orElse(false);
    }

    // ------------------------------------------------------------------ event handling

    /** Presence comes from the SFU, not from clients: JOINED only once the media connection really exists. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onMediaEvent(MediaWebhookEvent event) {
        if (event.participantIdentity() == null) {
            return;
        }
        UUID participantId;
        try {
            participantId = UUID.fromString(event.participantIdentity());
        } catch (IllegalArgumentException notOurs) {
            return; // e.g. egress recorder participants
        }
        Optional<UUID> meetingId = participants.findMeetingIdById(participantId);
        if (meetingId.isEmpty()) {
            return;
        }
        // The meeting row lock first, like every moderation change: the participation is then read after any change
        // the host is making to it has committed, so neither overwrites the other (e.g. a host's media override
        // landing as the connection is confirmed must not undo JOINED, nor be skipped for it).
        meetings.findByIdForUpdate(meetingId.get());
        Optional<MeetingParticipant> found = participants.findById(participantId);
        if (found.isEmpty()) {
            return;
        }
        MeetingParticipant participant = found.get();
        if (RoomNames.isAudience(event.roomName())) {
            onAudienceRoomEvent(participant, event);
            return;
        }
        Instant now = clock.instant();
        switch (event.type()) {
            case PARTICIPANT_JOINED -> {
                if (participant.isEndedConnection(event.participantSid())) {
                    // The join of a connection whose leave was already processed (events out of order): it is over.
                    log.debug("Ignoring late join of an ended connection of participant {}", participantId);
                    return;
                }
                switch (participant.getState()) {
                    case ADMITTED, JOINED -> {
                        markConnected(participant, now, event.participantSid());
                        applyCurrentGrants(participant, event.roomName());
                    }
                    case LEFT -> {
                        // Reconnecting with a token that is still within its TTL: the room may have been locked or
                        // filled up since this participant left, so admission is checked again (moderators exempt).
                        String refusal = reconnectRefusal(participant, now);
                        if (refusal == null) {
                            markConnected(participant, now, event.participantSid());
                            applyCurrentGrants(participant, event.roomName());
                        } else {
                            log.info("Ejecting reconnecting participant {}: {}", participantId, refusal);
                            if (event.roomName() != null) {
                                media.removeParticipant(event.roomName(), participantId.toString());
                            }
                        }
                    }
                    case REMOVED, DENIED, WAITING -> {
                        // A token issued before removal is still within its TTL: eject the connection immediately.
                        log.info("Ejecting participant {} in state {}", participantId, participant.getState());
                        if (event.roomName() != null) {
                            media.removeParticipant(event.roomName(), participantId.toString());
                        }
                    }
                }
            }
            case PARTICIPANT_LEFT -> disconnected(participant, event, now);
            default -> {
            }
        }
    }

    /**
     * An attendee's audience room in a HOST_AND_SELF_ONLY session. Presence is tracked in the stage room only (everyone
     * connects there), so connections here do not change state. Only the attendee it belongs to and the stage of that
     * session may be here, with the rights their role has now: anyone else (removed, still waiting, another attendee,
     * a demoted co-host with a token issued before the demotion) is disconnected; whoever stays gets its current
     * rights pushed, since the token may carry older ones.
     */
    private void onAudienceRoomEvent(MeetingParticipant participant, MediaWebhookEvent event) {
        if (event.type() != MediaWebhookEvent.Type.PARTICIPANT_JOINED) {
            return;
        }
        boolean owner = RoomNames.audienceOwner(event.roomName()).map(participant.getId()::equals).orElse(false);
        boolean sameSession = participant.getRoomId() != null && roomSessions.active(participant.getMeetingId())
                .filter(r -> r.getId().equals(participant.getRoomId()))
                .map(r -> r.getLivekitRoomName().equals(RoomNames.stageOf(event.roomName())))
                .orElse(false);
        boolean allowed = participant.isPresent() && sameSession
                && (participant.getRole().isModerator() ? !owner : owner);
        if (!allowed) {
            log.info("Ejecting participant {} (state {}, role {}) from audience room {}", participant.getId(),
                    participant.getState(), participant.getRole(), event.roomName());
            media.removeParticipant(event.roomName(), participant.getId().toString());
            return;
        }
        applyCurrentGrants(participant, event.roomName());
    }

    /**
     * Pushes the rights of the participant's current role to its connection in {@code roomName}, after commit. A
     * token stays valid for its TTL, so a connection may have been opened with the rights of an earlier role (e.g. a
     * co-host demoted before connecting); the SFU must enforce the role as it is now.
     */
    private void applyCurrentGrants(MeetingParticipant participant, String roomName) {
        if (roomName == null) {
            return;
        }
        UUID meetingId = participant.getMeetingId();
        UUID participantId = participant.getId();
        // From committed state after this commit: a host change committed meanwhile is never pushed over.
        AfterCommit.run(() -> grants.connection(meetingId, participantId, roomName));
    }

    /**
     * The SFU reports a connection closed. Ignored when it is an older connection than the participant's current one
     * (a reconnect whose old connection's event arrived late). Otherwise the participant is LEFT; what they were doing
     * in the room (raised hand, local recording) is cleared for everyone, and a host who dropped out without leaving
     * gets a successor like one who left through the app.
     */
    private void disconnected(MeetingParticipant participant, MediaWebhookEvent event, Instant now) {
        if (participant.isStaleConnection(event.participantSid())) {
            log.debug("Ignoring disconnect of an older connection of participant {}", participant.getId());
            return;
        }
        if (!participant.isOpen()) {
            return;
        }
        boolean wasRecording = participant.isRecordingLocally();
        boolean handRaised = participant.isHandRaised();
        boolean host = participant.getRole() == MeetingRole.HOST && participant.getState() != MeetingParticipant.State.WAITING;
        Meeting meeting = meetings.findById(participant.getMeetingId()).orElse(null);
        participant.markLeft(now);
        if (meeting == null || participant.getRoomId() == null) {
            return;
        }
        Optional<MeetingRoom> room = roomSessions.active(meeting.getId())
                .filter(r -> r.getId().equals(participant.getRoomId()));
        if (room.isEmpty()) {
            return;
        }
        audience.announceDeparture(meeting, room.get(), participant, wasRecording, handRaised);
        participants.flush();
        audience.announcePresence(meeting, room.get(), participant.getRole().isModerator());
        if (host && !meeting.isStageOnly()) {
            // A dropped connection is often a reload or a network blip: only appoint a successor if the host has not
            // come back after the grace period.
            scheduleSuccession(meeting.getId(), room.get().getId());
        }
    }

    private void scheduleSuccession(UUID meetingId, UUID roomId) {
        Runnable check = () -> {
            try {
                newTx.executeWithoutResult(s -> succeedIfHostStillAway(meetingId, roomId));
            } catch (RuntimeException e) {
                log.warn("Could not appoint a successor for the absent host of meeting {}: {}", meetingId, e.getMessage());
            }
        };
        AfterCommit.run(() -> {
            if (hostDropoutGrace.isZero() || hostDropoutGrace.isNegative()) {
                check.run();
            } else {
                scheduler.schedule(check, clock.instant().plus(hostDropoutGrace));
            }
        });
    }

    private void succeedIfHostStillAway(UUID meetingId, UUID roomId) {
        Meeting meeting = meetings.findByIdForUpdate(meetingId).orElse(null);
        if (meeting == null || meeting.isStageOnly()) {
            return;
        }
        roomSessions.active(meetingId).filter(r -> r.getId().equals(roomId)).ifPresent(room -> {
            boolean hostBack = participants.findByRoomIdAndStateIn(roomId, MeetingParticipant.PRESENT).stream()
                    .anyMatch(p -> p.getRole() == MeetingRole.HOST);
            if (!hostBack) {
                ensureModeratorRemains(meeting, room);
            }
        });
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSessionEnded(RoomEvents.SessionEnded event) {
        Instant now = clock.instant();
        List<MeetingParticipant> open = participants.findByRoomIdAndStateIn(event.roomId(), MeetingParticipant.OPEN);
        // HOST_AND_SELF_ONLY: close every attendee audience room with the session (the mode cannot change while live).
        List<String> audienceRooms = meetings.findById(event.meetingId()).filter(Meeting::isStageOnly)
                .map(m -> participants.findByRoomIdAndStateIn(event.roomId(), java.util.EnumSet.allOf(MeetingParticipant.State.class))
                        .stream().filter(p -> ParticipantPolicy.isMasked(m, p.getRole()))
                        .map(p -> RoomNames.audience(event.roomName(), p.getId())).toList())
                .orElse(List.of());
        if (!audienceRooms.isEmpty()) {
            AfterCommit.run(() -> audienceRooms.forEach(name -> {
                try {
                    media.deleteRoom(name);
                } catch (RuntimeException e) {
                    log.warn("Could not delete audience room {}: {}", name, e.getMessage());
                }
            }));
        }
        open.forEach(p -> p.markLeft(now));
        participants.findWithoutRoom(event.meetingId(), MeetingParticipant.State.WAITING).forEach(p -> p.markLeft(now));
    }

    /**
     * The SFU confirmed the connection. The session's peak and the count everyone sees come from the authoritative
     * membership (one per person); a repeated or reconnect event for someone already JOINED changes neither.
     */
    private void markConnected(MeetingParticipant participant, Instant now, String connectionSid) {
        boolean newlyJoined = participant.getState() != MeetingParticipant.State.JOINED;
        participant.markJoined(now, connectionSid);
        if (participant.getRoomId() == null) {
            return;
        }
        participants.flush();
        roomSessions.observeParticipantCount(participant.getRoomId(), audience.presentCount(participant.getRoomId()));
        if (newlyJoined) {
            meetings.findById(participant.getMeetingId()).ifPresent(meeting -> roomSessions.active(meeting.getId())
                    .filter(r -> r.getId().equals(participant.getRoomId()))
                    .ifPresent(room -> audience.announcePresence(meeting, room, participant.getRole().isModerator())));
        }
    }

    /** Why a LEFT participant may not reconnect to its session now, or null if it may. */
    private String reconnectRefusal(MeetingParticipant participant, Instant now) {
        if (!participant.wasAdmitted()) {
            return "NEVER_ADMITTED";
        }
        Optional<MeetingRoom> room = roomSessions.active(participant.getMeetingId())
                .filter(r -> r.getId().equals(participant.getRoomId()));
        if (room.isEmpty()) {
            return "SESSION_ENDED";
        }
        // A reload, second tab or role-change reconnect whose old connection's leave was processed first takes its
        // seat back, even if the room was locked or filled meanwhile.
        if (participant.getRole().isModerator() || participant.leftWithin(now, reconnectWindow)) {
            return null;
        }
        Meeting meeting = meetings.findById(participant.getMeetingId()).orElse(null);
        if (meeting == null || meeting.isLocked()) {
            return "LOCKED";
        }
        if (participants.countOccupiedSeats(room.get().getId(), now.minus(JoinService.ADMISSION_GRACE))
                >= meeting.getMaxParticipants()) {
            return "FULL";
        }
        return null;
    }

    /**
     * When the host leaves a running session without a co-host, the earliest signed-in participant is promoted
     * so the meeting keeps a moderator ("leave while the meeting continues").
     */
    private void ensureModeratorRemains(Meeting meeting, MeetingRoom room) {
        List<MeetingParticipant> present = participants.findByRoomIdAndStateIn(room.getId(), MeetingParticipant.PRESENT);
        if (present.isEmpty() || present.stream().anyMatch(p -> p.getRole().isModerator())) {
            return;
        }
        present.stream()
                .filter(p -> p.getUserId() != null)
                .min(Comparator.comparing(p -> p.getJoinedAt() == null ? Instant.MAX : p.getJoinedAt()))
                .ifPresent(successor -> {
                    successor.setRole(MeetingRole.CO_HOST);
                    signals.broadcast(room.getLivekitRoomName(), RoomSignals.TOPIC_MODERATION, Map.of(
                            "type", "role-changed", "participantId", successor.getId().toString(), "role", "CO_HOST"));
                    // SFU call after commit: never hold the meeting row lock (and a DB connection) during HTTP.
                    String metadata = policy.metadataFor(meeting, successor);
                    var permissions = policy.permissionsFor(meeting, successor);
                    String stageName = meeting.isStageOnly() ? policy.visibleName(meeting, successor) : null;
                    AfterCommit.run(() -> {
                        try {
                            media.updateParticipant(room.getLivekitRoomName(), successor.getId().toString(),
                                    metadata, permissions);
                            if (stageName != null) {
                                // A webinar attendee joins the stage: everyone may now see their real name.
                                media.renameParticipant(room.getLivekitRoomName(), successor.getId().toString(),
                                        stageName);
                            }
                        } catch (RuntimeException e) {
                            log.warn("Could not update media permissions for promoted co-host: {}", e.getMessage());
                        }
                    });
                    audit.record(null, successor.getId(), AuditActions.MOD_AUTO_PROMOTE, AuditActions.RESOURCE_MEETING,
                            meeting.getId(), Map.of("participantId", successor.getId()));
                });
    }
}
