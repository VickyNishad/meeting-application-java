package com.example.meetplatform.participant.service;

import com.example.meetplatform.common.persistence.AfterCommit;
import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.room.entity.MeetingRoom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may see what a participant does in the room. In a normal meeting everyone sees everything. In a
 * HOST_AND_SELF_ONLY session what an attendee does (chat, hand, reactions, local recording) reaches only the stage (host
 * and co-hosts present) and the attendee; what the stage does reaches everyone.
 */
@Component
public class RoomAudience {

    private static final Logger log = LoggerFactory.getLogger(RoomAudience.class);
    private static final Set<MeetingRole> ALL_ROLES = EnumSet.allOf(MeetingRole.class);
    private static final Set<MeetingRole> MODERATOR_ROLES = EnumSet.of(MeetingRole.HOST, MeetingRole.CO_HOST);

    private final MeetingParticipantRepository participants;
    private final RoomSignals signals;
    private final TaskScheduler scheduler;
    private final TransactionTemplate readTx;
    private final Duration presenceDebounce;
    private final Clock clock;
    /** Rooms with a participant-count announcement scheduled, and whether the stage changed since the last one. */
    private final Set<UUID> scheduledRooms = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> pendingStageChange = new ConcurrentHashMap<>();

    public RoomAudience(MeetingParticipantRepository participants, RoomSignals signals, TaskScheduler scheduler,
                        PlatformTransactionManager transactions,
                        @Value("${app.meetings.presence-debounce:PT0.25S}") Duration presenceDebounce, Clock clock) {
        this.participants = participants;
        this.signals = signals;
        this.scheduler = scheduler;
        // Runs after commit or on a scheduler thread: always in a read-only transaction of its own.
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
        this.presenceDebounce = presenceDebounce;
        this.clock = clock;
    }

    /** Media identities of the host and co-hosts present in the session. */
    public List<String> stageIdentities(UUID roomId) {
        return participants.findByRoomIdAndStateIn(roomId, MeetingParticipant.PRESENT).stream()
                .filter(p -> p.getRole().isModerator())
                .map(p -> p.getId().toString())
                .toList();
    }

    /**
     * People in the session: participants whose media connection the SFU confirmed (JOINED), counted once per person
     * (a signed-in user with a reconnected or second participation counts once). Admitted-but-never-connected and
     * stale rows are not people in the room. One counting query, so it stays cheap in large rooms.
     */
    public int presentCount(UUID roomId) {
        return (int) participants.countPeople(roomId, MeetingParticipant.State.JOINED, ALL_ROLES);
    }

    /** The count a viewer may see: everyone, except that a HOST_AND_SELF_ONLY attendee only counts the stage and itself. */
    public int visibleCount(Meeting meeting, UUID roomId, MeetingParticipant viewer) {
        if (!stageOnly(meeting, viewer)) {
            return presentCount(roomId);
        }
        int self = viewer.getState() == MeetingParticipant.State.JOINED ? 1 : 0;
        return stageCount(roomId) + self;
    }

    private int stageCount(UUID roomId) {
        return (int) participants.countPeople(roomId, MeetingParticipant.State.JOINED, MODERATOR_ROLES);
    }

    public void announcePresence(Meeting meeting, MeetingRoom room) {
        announcePresence(meeting, room, false);
    }

    /**
     * Someone joined or left: tells who may know the new count (everyone; in HOST_AND_SELF_ONLY the stage). When the
     * stage itself changed ({@code stageChanged}), HOST_AND_SELF_ONLY attendees are also sent their own count (stage
     * plus themselves), unnumbered; they are told nothing when only other attendees come and go.
     *
     * <p>The count is read after the change commits, in a transaction of its own (a count read inside the change's
     * transaction misses concurrent changes that commit around it), and announcements for one room within
     * {@code app.meetings.presence-debounce} are coalesced into one, so a join storm sends one update, not one per
     * join.
     */
    public void announcePresence(Meeting meeting, MeetingRoom room, boolean stageChanged) {
        UUID roomId = room.getId();
        String roomName = room.getLivekitRoomName();
        boolean stageOnlyMeeting = meeting.isStageOnly();
        AfterCommit.run(() -> {
            pendingStageChange.merge(roomId, stageChanged, Boolean::logicalOr);
            if (!scheduledRooms.add(roomId)) {
                return; // an announcement for this room is already on its way and will read the latest count
            }
            Runnable send = () -> {
                // In this order: a change arriving from here on schedules a new announcement.
                scheduledRooms.remove(roomId);
                boolean stage = Boolean.TRUE.equals(pendingStageChange.remove(roomId));
                try {
                    readTx.executeWithoutResult(s -> sendPresence(roomId, roomName, stageOnlyMeeting, stage));
                } catch (RuntimeException e) {
                    log.warn("Could not announce the participant count of room {}: {}", roomName, e.getMessage());
                }
            };
            if (presenceDebounce.isZero() || presenceDebounce.isNegative()) {
                send.run();
            } else {
                scheduler.schedule(send, clock.instant().plus(presenceDebounce));
            }
        });
    }

    private void sendPresence(UUID roomId, String roomName, boolean stageOnlyMeeting, boolean stageChanged) {
        Map<String, Object> payload = Map.of("type", "presence", "count", presentCount(roomId));
        if (!stageOnlyMeeting) {
            signals.broadcast(roomName, RoomSignals.TOPIC_PRESENCE, payload);
            return;
        }
        signals.sendToStage(roomName, RoomSignals.TOPIC_PRESENCE, payload, stageIdentities(roomId), List.of());
        if (stageChanged) {
            List<String> attendees = participants.findByRoomIdAndStateIn(roomId, MeetingParticipant.PRESENT).stream()
                    .filter(p -> !p.getRole().isModerator()).map(p -> p.getId().toString()).toList();
            signals.sendTo(roomName, RoomSignals.TOPIC_PRESENCE,
                    Map.of("type", "presence", "count", stageCount(roomId) + 1), attendees);
        }
    }

    /** True when only the stage (and the actor) may see what {@code actor} does. */
    public static boolean stageOnly(Meeting meeting, MeetingParticipant actor) {
        return meeting.isStageOnly() && !actor.getRole().isModerator();
    }

    /** Sends after commit, to the room or (webinar attendee) to the stage plus the actor. */
    public void announce(Meeting meeting, MeetingRoom room, String topic, Map<String, ?> payload,
                         MeetingParticipant actor) {
        if (stageOnly(meeting, actor)) {
            signals.sendToStage(room.getLivekitRoomName(), topic, payload, stageIdentities(room.getId()),
                    List.of(actor.getId().toString()));
        } else {
            signals.broadcast(room.getLivekitRoomName(), topic, payload);
        }
    }

    /**
     * Someone left (through the app, removed, or dropped by the SFU): what they were doing in the room is over, so
     * everyone who saw it is told, the same way they were told it started.
     */
    public void announceDeparture(Meeting meeting, MeetingRoom room, MeetingParticipant actor, boolean wasRecording,
                                  boolean handRaised) {
        if (wasRecording) {
            announce(meeting, room, RoomSignals.TOPIC_RECORDING, Map.of("type", "recording", "kind", "LOCAL",
                    "state", "STOPPED", "participantId", actor.getId().toString()), actor);
        }
        if (handRaised) {
            announce(meeting, room, RoomSignals.TOPIC_HAND, Map.of("type", "hand",
                    "participantId", actor.getId().toString(), "raised", false), actor);
        }
    }

    /** Like {@link #announce} but delivered now; throws if the room cannot be notified (consent notices). */
    public void announceNow(Meeting meeting, MeetingRoom room, String topic, Map<String, ?> payload,
                            MeetingParticipant actor) {
        if (stageOnly(meeting, actor)) {
            signals.sendToStageNow(room.getLivekitRoomName(), topic, payload, stageIdentities(room.getId()),
                    List.of(actor.getId().toString()));
        } else {
            signals.broadcastNow(room.getLivekitRoomName(), topic, payload);
        }
    }
}
