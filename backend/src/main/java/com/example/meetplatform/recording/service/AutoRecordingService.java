package com.example.meetplatform.recording.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.persistence.AfterCommit;
import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaMode;
import com.example.meetplatform.meeting.event.MeetingEvents;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.recording.entity.Recording;
import com.example.meetplatform.recording.repository.RecordingRepository;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.service.RoomSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Auto-record: when a meeting has {@code autoRecord} on, each session gets one composite recording of the room that
 * follows the active speaker, started when someone first speaks (see {@link MeetingEvents.SpeechDetected}): a session
 * in which nobody speaks leaves no recording, not even an empty one. There are no per-participant
 * files (older PARTICIPANT recordings stay readable). In a webinar the composite shows the stage only, because
 * attendees' tracks are not subscribable by anyone but the stage. The host turned this on, and the join page and
 * the room both say the meeting is recorded; the capture is still announced to the room before it starts and does
 * not start if the room cannot be notified.
 *
 * <p>The recording starts after the transaction that recorded the speech commits, in its own transaction: egress
 * calls never hold that transaction, and a slow or missing egress worker cannot fail it. Later speech finds the
 * in-flight recording and returns without calling the media server; a partial unique index keeps it to one in-flight
 * room recording when speech from several people races.
 */
@Service
public class AutoRecordingService {

    private static final Logger log = LoggerFactory.getLogger(AutoRecordingService.class);
    /** How long after a failed automatic start of a room recording before speech tries again. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(5);

    private final MeetingParticipantRepository participants;
    private final MeetingRepository meetings;
    private final RecordingRepository recordings;
    private final RoomSessionService roomSessions;
    private final MediaService media;
    private final RoomSignals signals;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final AppProperties properties;
    private final Clock clock;

    public AutoRecordingService(MeetingParticipantRepository participants, MeetingRepository meetings,
                                RecordingRepository recordings, RoomSessionService roomSessions, MediaService media,
                                RoomSignals signals, AuditService audit, PlatformTransactionManager transactions,
                                AppProperties properties, Clock clock) {
        this.participants = participants;
        this.meetings = meetings;
        this.recordings = recordings;
        this.roomSessions = roomSessions;
        this.media = media;
        this.signals = signals;
        this.audit = audit;
        // Runs from an after-commit callback, where the finished transaction is still bound to the thread: work must
        // run in a new transaction or it would silently join the committed one and never be written.
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Someone spoke: the session's recording starts now if it is not running yet. Joining alone records nothing, so a
     * session in which nobody speaks leaves no recording.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSpeechDetected(MeetingEvents.SpeechDetected event) {
        if (!properties.recording().cloudEnabled()) {
            return;
        }
        UUID participantId = event.participantId();
        AfterCommit.run(() -> {
            try {
                startFor(participantId);
            } catch (RuntimeException e) {
                // Never fail what reported the speech (a speaking clip, speaking time): it is already committed.
                log.warn("Auto-recording for participant {} could not be started: {}", participantId, e.getMessage());
            }
        });
    }

    /**
     * Switched off: stop everything auto-record is capturing (room and participants), announce it, and let later
     * joiners in unrecorded. Switched on: recordings a moderator had stopped may start again on the next speech.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAutoRecordChanged(MeetingEvents.AutoRecordChanged event) {
        if (event.enabled()) {
            recordings.clearAutoSuppression(event.meetingId());
            return;
        }
        Optional<MeetingRoom> room = roomSessions.active(event.meetingId());
        for (Recording r : recordings.findByMeetingIdAndAutoStartedTrueAndStatusIn(event.meetingId(),
                List.of(Recording.Status.STARTING, Recording.Status.ACTIVE))) {
            r.setStatus(Recording.Status.STOPPING);
            String egressId = r.getEgressId();
            if (egressId != null) {
                // SFU call after commit: never hold the meeting row lock (and a DB connection) during HTTP.
                AfterCommit.run(() -> {
                    try {
                        media.stopRecording(egressId);
                    } catch (RuntimeException e) {
                        log.warn("Could not stop auto-recording egress {}: {}", egressId, e.getMessage());
                    }
                });
            }
            room.filter(rm -> rm.getId().equals(r.getRoomId())).ifPresent(rm -> signals.broadcast(
                    rm.getLivekitRoomName(), RoomSignals.TOPIC_RECORDING, RecordingSignals.of(r, "STOPPED")));
            audit.record(AuditActions.RECORDING_STOPPED, AuditActions.RESOURCE_RECORDING, r.getId(),
                    Map.of("meetingId", event.meetingId(), "reason", "AUTO_RECORD_OFF"));
        }
    }

    void startFor(UUID participantId) {
        Target target = tx.execute(s -> target(participantId).orElse(null));
        if (target == null) {
            return;
        }
        attempt("room " + target.roomId(), () -> startRoom(target));
    }

    /** What to record, only when the participant really is in the live session of an auto-recorded meeting. */
    private Optional<Target> target(UUID participantId) {
        MeetingParticipant p = participants.findById(participantId).orElse(null);
        // Admitted or connected: a speaking clip may be opened just before the SFU's "joined" notice is processed.
        if (p == null || !p.isPresent() || p.getRoomId() == null) {
            return Optional.empty();
        }
        Meeting meeting = meetings.findById(p.getMeetingId()).orElse(null);
        if (meeting == null || !meeting.isAutoRecord()) {
            return Optional.empty();
        }
        return roomSessions.active(meeting.getId()).filter(r -> r.getId().equals(p.getRoomId()))
                .map(room -> new Target(meeting.getId(), room.getId(), room.getLivekitRoomName(),
                        meeting.getMediaMode() == MediaMode.AUDIO_ONLY, meeting.getVisibilityMode().name(),
                        meeting.isStageOnly()));
    }

    private void startRoom(Target t) {
        tx.executeWithoutResult(s -> {
            if (!recordings.findByRoomIdAndKindAndStatusIn(t.roomId(), Recording.KIND_CLOUD, Recording.IN_FLIGHT)
                    .isEmpty() || recordings.existsByRoomIdAndKindAndAutoSuppressedTrue(t.roomId(), Recording.KIND_CLOUD)) {
                return;
            }
            // Started by speech, which comes often: after a failure (e.g. no recorder available) it is tried again
            // only after a pause, not once per sentence, so a broken recorder does not leave a failed entry per turn.
            if (recordings.existsByRoomIdAndKindAndAutoStartedTrueAndStatusAndCreatedAtAfter(t.roomId(),
                    Recording.KIND_CLOUD, Recording.Status.FAILED, clock.instant().minus(RETRY_AFTER_FAILURE))) {
                return;
            }
            Recording r = Recording.autoRoom(t.meetingId(), t.roomId(), t.audioOnly(), clock.instant());
            r.setStorageKey(prefix(t) + r.getId() + (t.audioOnly() ? ".ogg" : ".mp4"));
            r.describeCapture(t.visibilityMode(), t.stageOnly());
            recordings.saveAndFlush(r);
            capture(r, t,
                    () -> media.startRoomRecording(t.roomName(), r.getStorageKey(), r.getLayout(), t.audioOnly()));
        });
    }

    /** Announce first, then capture; either failure leaves a FAILED recording instead of a silent one. */
    private void capture(Recording r, Target t, Supplier<String> start) {
        try {
            signals.broadcastNow(t.roomName(), RoomSignals.TOPIC_RECORDING, RecordingSignals.of(r, "STARTED"));
        } catch (ApiException e) {
            failed(r, t, "Could not notify the room: " + e.getMessage(), "NOTIFY");
            return;
        }
        try {
            r.setEgressId(start.get());
        } catch (ApiException e) {
            failed(r, t, "Could not start recording: " + e.getMessage(), "START");
            signals.broadcast(t.roomName(), RoomSignals.TOPIC_RECORDING, RecordingSignals.of(r, "FAILED"));
            return;
        }
        Map<String, Object> details = new HashMap<>();
        details.put("meetingId", t.meetingId());
        details.put("kind", r.getKind());
        details.put("auto", true);
        if (r.getParticipantId() != null) {
            details.put("participantId", r.getParticipantId());
        }
        audit.record(null, null, AuditActions.RECORDING_STARTED, AuditActions.RESOURCE_RECORDING, r.getId(), details);
    }

    private void failed(Recording r, Target t, String reason, String stage) {
        r.fail(reason, clock.instant());
        audit.record(null, null, AuditActions.RECORDING_FAILED, AuditActions.RESOURCE_RECORDING, r.getId(),
                Map.of("meetingId", t.meetingId(), "stage", stage, "auto", true));
    }

    private static void attempt(String what, Runnable action) {
        try {
            action.run();
        } catch (DataIntegrityViolationException raced) {
            log.debug("Auto-recording for {} already started by a concurrent webhook", what);
        }
    }

    private String prefix(Target t) {
        return properties.recording().filePrefix() + "/" + t.meetingId() + "/";
    }

    private record Target(UUID meetingId, UUID roomId, String roomName, boolean audioOnly, String visibilityMode,
                          boolean stageOnly) {
    }
}
