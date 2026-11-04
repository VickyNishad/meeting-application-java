package com.example.meetplatform.participant.service;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.persistence.AfterCommit;
import com.example.meetplatform.media.dto.MediaPermissions;
import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.media.service.RoomNames;
import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.event.MeetingEvents;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.participant.dto.RoomStateDtos.MediaPermissionsView;
import com.example.meetplatform.participant.dto.RoomStateDtos.ParticipantMedia;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.service.RoomSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Brings the SFU in line with the media rights stored for a meeting: what each connected participant may publish,
 * and the signals that tell clients about it.
 *
 * <p>Always called after the change has committed, and always from a fresh read: whatever ran before, the grants
 * pushed are the ones in force now. Pushes for one meeting run one at a time, so two changes in quick succession
 * can never leave the older one on the SFU or on clients. No database lock is held during the SFU calls. (The
 * ordering holds within one backend instance.)
 */
@Component
public class MediaGrantSync {

    private static final Logger log = LoggerFactory.getLogger(MediaGrantSync.class);
    private static final int STRIPES = 64;

    private final MeetingRepository meetings;
    private final MeetingParticipantRepository participants;
    private final RoomSessionService roomSessions;
    private final ParticipantPolicy policy;
    private final MediaService media;
    private final RoomSignals signals;
    private final TransactionTemplate readTx;
    private final Object[] stripes = new Object[STRIPES];

    public MediaGrantSync(MeetingRepository meetings, MeetingParticipantRepository participants,
                          RoomSessionService roomSessions, ParticipantPolicy policy, MediaService media,
                          RoomSignals signals, PlatformTransactionManager transactions) {
        this.meetings = meetings;
        this.participants = participants;
        this.roomSessions = roomSessions;
        this.policy = policy;
        this.media = media;
        this.signals = signals;
        // Also called after another transaction's commit: always a transaction of its own.
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new Object();
        }
    }

    /**
     * The meeting's permissions changed: every participant of the live session gets its current grants, everyone
     * the meeting-wide permissions, the host every participant's effective rights. Throws if the SFU refused an
     * update (after trying all of them).
     */
    public void everyone(UUID meetingId) {
        run(meetingId, null, true, false, null, true);
    }

    /**
     * These participants' rights changed (override, role): they get their current grants; the host (and with
     * {@code toThemselves} the participants themselves) their effective rights. Throws like {@link #everyone}.
     */
    public void participants(UUID meetingId, Collection<UUID> participantIds, boolean toThemselves) {
        run(meetingId, participantIds, false, toThemselves, null, true);
    }

    /** Tells the host these participants' effective rights (their grants were already pushed by the caller). */
    public void announceToHost(UUID meetingId, Collection<UUID> participantIds) {
        run(meetingId, participantIds, false, false, null, false);
    }

    /**
     * A connection was just confirmed by the SFU: its token may carry older rights than the current ones. Pushes
     * them to that connection only; one that cannot be updated is disconnected rather than left with rights it may
     * no longer have.
     */
    public void connection(UUID meetingId, UUID participantId, String roomName) {
        try {
            run(meetingId, List.of(participantId), false, false, roomName, true);
        } catch (RuntimeException e) {
            log.warn("Could not apply current rights to {} in {}, disconnecting: {}", participantId, roomName,
                    e.getMessage());
            try {
                media.removeParticipant(roomName, participantId.toString());
            } catch (RuntimeException ignored) {
                // Nothing more can be done from here; the token expires with its TTL.
            }
        }
    }

    /** Media mode or screen-sharing policy edited through the meeting form: enforced like a host change. */
    @EventListener
    public void onMediaSettingsChanged(MeetingEvents.MediaSettingsChanged event) {
        AfterCommit.run(() -> {
            try {
                everyone(event.meetingId());
            } catch (RuntimeException e) {
                log.warn("Could not apply changed media settings of meeting {}: {}", event.meetingId(), e.getMessage());
            }
        });
    }

    private void run(UUID meetingId, Collection<UUID> only, boolean meetingWide, boolean toThemselves,
                     String onlyRoom, boolean pushGrants) {
        synchronized (stripes[Math.floorMod(meetingId.hashCode(), STRIPES)]) {
            Plan plan = readTx.execute(s -> plan(meetingId, only, meetingWide, onlyRoom));
            if (plan == null) {
                return;
            }
            ApiException failure = null;
            for (Grant g : pushGrants ? plan.grants : List.<Grant>of()) {
                try {
                    media.updateParticipant(g.roomName, g.identity, g.metadata, g.permissions);
                } catch (ApiException e) {
                    log.warn("Could not update media permissions of {} in {}: {}", g.identity, g.roomName,
                            e.getMessage());
                    failure = e;
                }
            }
            // Signals describe committed state and go out now, in the same order as the grants.
            if (onlyRoom == null) {
                if (meetingWide) {
                    MediaPermissionsView v = plan.permissions;
                    signals.broadcastCommitted(plan.stageRoom, RoomSignals.TOPIC_MODERATION, Map.of(
                            "type", "media-permissions", "microphone", v.microphone().name(),
                            "camera", v.camera().name(), "screenShare", v.screenShare().name()));
                }
                if (!plan.hosts.isEmpty() && !plan.media.isEmpty()) {
                    signals.sendToCommitted(plan.stageRoom, RoomSignals.TOPIC_MODERATION,
                            Map.of("type", "participant-media", "participants", plan.media), plan.hosts);
                }
                if (toThemselves) {
                    for (ParticipantMedia m : plan.media) {
                        if (!plan.hosts.contains(m.participantId())) {
                            signals.sendToCommitted(plan.stageRoom, RoomSignals.TOPIC_MODERATION,
                                    Map.of("type", "participant-media", "participants", List.of(m)),
                                    List.of(m.participantId()));
                        }
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** What to push, read from committed state; null when the meeting has no live session. */
    private Plan plan(UUID meetingId, Collection<UUID> only, boolean meetingWide, String onlyRoom) {
        Meeting meeting = meetings.findById(meetingId).orElse(null);
        if (meeting == null) {
            return null;
        }
        MeetingRoom room = roomSessions.active(meetingId).orElse(null);
        if (room == null) {
            return null;
        }
        String stageRoom = room.getLivekitRoomName();
        // Everyone admitted to the session, connected or about to be: a connection that is not confirmed yet gets
        // the update too (ignored by the SFU if it is not there).
        List<MeetingParticipant> present = participants.findByRoomIdAndStateIn(room.getId(), MeetingParticipant.PRESENT);
        List<MeetingParticipant> targets = only == null ? present
                : present.stream().filter(p -> only.contains(p.getId())).toList();
        List<Grant> grants = new ArrayList<>();
        for (MeetingParticipant p : targets) {
            String identity = p.getId().toString();
            String metadata = policy.metadataFor(meeting, p);
            String audienceRoom = RoomNames.audience(stageRoom, p.getId());
            if (onlyRoom == null || onlyRoom.equals(stageRoom)) {
                grants.add(new Grant(stageRoom, identity, metadata, policy.permissionsFor(meeting, p)));
            }
            if (meeting.isStageOnly() && (onlyRoom == null || onlyRoom.equals(audienceRoom))) {
                // Its own audience room: an attendee publishes there; once promoted it may only watch.
                grants.add(new Grant(audienceRoom, identity, metadata, policy.audiencePermissionsFor(meeting, p)));
            }
            if (onlyRoom != null && RoomNames.isAudience(onlyRoom) && !onlyRoom.equals(audienceRoom)) {
                // The stage watching an attendee's audience room.
                grants.add(new Grant(onlyRoom, identity, metadata, policy.audiencePermissionsFor(meeting, p)));
            }
        }
        List<String> hosts = present.stream().filter(p -> p.getRole() == MeetingRole.HOST)
                .map(p -> p.getId().toString()).toList();
        List<ParticipantMedia> changed = (meetingWide ? present : targets).stream()
                .map(p -> policy.participantMedia(meeting, p)).toList();
        return new Plan(stageRoom, grants, policy.mediaPermissions(meeting), hosts, changed);
    }

    private record Grant(String roomName, String identity, String metadata, MediaPermissions permissions) {
    }

    private record Plan(String stageRoom, List<Grant> grants, MediaPermissionsView permissions, List<String> hosts,
                        List<ParticipantMedia> media) {
    }
}
