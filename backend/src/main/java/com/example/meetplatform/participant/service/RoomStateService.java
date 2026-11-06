package com.example.meetplatform.participant.service;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.security.RateLimiter;
import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.media.service.RoomSequence;
import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.meeting.service.MeetingRecordingLookup;
import com.example.meetplatform.participant.dto.RoomStateDtos.Actor;
import com.example.meetplatform.participant.dto.RoomStateDtos.AudienceAccessView;
import com.example.meetplatform.participant.dto.RoomStateDtos.AudienceRoomView;
import com.example.meetplatform.participant.dto.RoomStateDtos.ParticipantMedia;
import com.example.meetplatform.participant.dto.RoomStateDtos.RoomStateView;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.service.RoomSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The live session as the server knows it ({@link RoomStateView}), and participant actions that are part of that
 * state or relayed to the room: raised hands and reactions. Participants cannot publish on the media data channel;
 * these go through the API, which checks who is asking and decides who may see it (see {@link RoomAudience}).
 */
@Service
public class RoomStateService {

    /** Same set the clients offer. */
    public static final Set<String> REACTION_EMOJI = Set.of("👍", "👏", "❤️", "😂", "😮", "🎉", "🙌", "🤔");

    private final MeetingRepository meetings;
    private final MeetingParticipantRepository participants;
    private final RoomSessionService roomSessions;
    private final ParticipantAccessService participantAccess;
    private final MeetingRecordingLookup recordings;
    private final RoomSequence sequence;
    private final RoomAudience audience;
    private final RateLimiter rateLimiter;
    private final ParticipantPolicy policy;
    private final MediaService media;
    private final AppProperties properties;
    private final Clock clock;

    public RoomStateService(MeetingRepository meetings, MeetingParticipantRepository participants,
                            RoomSessionService roomSessions, ParticipantAccessService participantAccess,
                            MeetingRecordingLookup recordings, RoomSequence sequence, RoomAudience audience,
                            RateLimiter rateLimiter, ParticipantPolicy policy, MediaService media,
                            AppProperties properties, Clock clock) {
        this.properties = properties;
        this.policy = policy;
        this.media = media;
        this.meetings = meetings;
        this.participants = participants;
        this.roomSessions = roomSessions;
        this.participantAccess = participantAccess;
        this.recordings = recordings;
        this.sequence = sequence;
        this.audience = audience;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RoomStateView state(UUID meetingId, UUID participantId, String token, Optional<CurrentUser> user) {
        MeetingParticipant viewer = participantAccess.resolvePresent(meetingId, participantId, token, user);
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        MeetingRoom room = liveRoom(meetingId, viewer);
        boolean stage = viewer.getRole().isModerator();
        // Numbers first, then state: a signal numbered after this read describes a change this read may already
        // include (applying it again is harmless); one numbered before it is included.
        RoomSequence.Number all = sequence.current(room.getLivekitRoomName(), RoomSequence.STREAM_ALL);
        Long stageSeq = stage ? sequence.current(room.getLivekitRoomName(), RoomSequence.STREAM_STAGE).seq() : null;

        List<MeetingParticipant> present = participants.findByRoomIdAndStateIn(room.getId(), MeetingParticipant.PRESENT);
        List<String> stageIdentities = present.stream().filter(p -> p.getRole().isModerator())
                .map(p -> p.getId().toString()).toList();
        // In a webinar an attendee sees only the stage's and their own activity; the stage sees everyone's.
        boolean restricted = meeting.isStageOnly() && !stage;
        List<MeetingParticipant> visible = present.stream()
                .filter(p -> !restricted || p.getRole().isModerator() || p.getId().equals(viewer.getId()))
                .toList();
        List<Actor> localRecorders = visible.stream().filter(MeetingParticipant::isRecordingLocally)
                .map(p -> actor(p, null)).toList();
        List<Actor> hands = visible.stream().filter(MeetingParticipant::isHandRaised)
                .sorted(Comparator.comparing(MeetingParticipant::getHandRaisedAt))
                .map(p -> actor(p, p.getHandRaisedAt())).toList();
        Integer waiting = stage ? participants.findByMeetingIdAndStateOrderByCreatedAtAsc(meetingId,
                MeetingParticipant.State.WAITING).size() : null;
        List<Actor> names = stage && meeting.isStageOnly() ? present.stream().map(p -> actor(p, p.getJoinedAt())).toList()
                : null;
        // Media rights: everyone sees the meeting-wide permissions and their own; only the host, who changes them,
        // sees everyone's.
        List<ParticipantMedia> participantMedia = viewer.getRole() == MeetingRole.HOST
                ? present.stream().map(p -> policy.participantMedia(meeting, p)).toList() : null;
        return new RoomStateView(all.epoch(), all.seq(), stageSeq, viewer.getId(), viewer.getRole(), meeting.getVisibilityMode(),
                stageIdentities, meeting.isLocked(), meeting.isChatEnabled(), meeting.getScreenSharePolicy(),
                meeting.isWaitingRoomEnabled(), recordings.isRecording(meetingId), meeting.isAutoRecord(),
                localRecorders, hands, waiting, names, audience.visibleCount(meeting, room.getId(), viewer),
                policy.mediaPermissions(meeting), policy.participantMedia(meeting, viewer), participantMedia,
                properties.recording().cloudEnabled());
    }

    /**
     * Credentials for the audience rooms of a HOST_AND_SELF_ONLY session (see {@link ParticipantPolicy#audienceRooms}).
     * The stage asks again whenever an attendee joins, and everyone asks again to reconnect (tokens are short-lived).
     */
    @Transactional(readOnly = true)
    public AudienceAccessView audienceAccess(UUID meetingId, UUID participantId, String token, Optional<CurrentUser> user) {
        MeetingParticipant viewer = participantAccess.resolvePresent(meetingId, participantId, token, user);
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        MeetingRoom room = liveRoom(meetingId, viewer);
        List<MeetingParticipant> present = viewer.getRole().isModerator()
                ? participants.findByRoomIdAndStateIn(room.getId(), MeetingParticipant.PRESENT) : List.of();
        List<AudienceRoomView> rooms = policy.audienceRooms(meeting, viewer, room.getLivekitRoomName(), present).stream()
                .map(r -> new AudienceRoomView(r.attendeeId(), r.roomName(), r.token())).toList();
        return new AudienceAccessView(media.clientUrl(), rooms);
    }

    @Transactional
    public void hand(UUID meetingId, UUID participantId, boolean raised, String token, Optional<CurrentUser> user) {
        MeetingParticipant p = participantAccess.resolvePresent(meetingId, participantId, token, user);
        rateLimiter.check("hand", participantId.toString(), 20, Duration.ofMinutes(1));
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        MeetingRoom room = liveRoom(meetingId, p);
        if (p.isHandRaised() == raised) {
            return;
        }
        p.setHandRaised(raised, clock.instant());
        audience.announce(meeting, room, RoomSignals.TOPIC_HAND, Map.of("type", "hand",
                "participantId", p.getId().toString(), "displayName", p.getDisplayName(), "raised", raised), p);
    }

    @Transactional(readOnly = true)
    public void reaction(UUID meetingId, UUID participantId, String emoji, String token, Optional<CurrentUser> user) {
        if (!REACTION_EMOJI.contains(emoji)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported reaction.");
        }
        MeetingParticipant p = participantAccess.resolvePresent(meetingId, participantId, token, user);
        rateLimiter.check("reaction", participantId.toString(), 10, Duration.ofSeconds(10));
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        MeetingRoom room = liveRoom(meetingId, p);
        audience.announce(meeting, room, RoomSignals.TOPIC_REACTIONS, Map.of("type", "reaction",
                "participantId", p.getId().toString(), "displayName", p.getDisplayName(), "emoji", emoji), p);
    }

    private MeetingRoom liveRoom(UUID meetingId, MeetingParticipant participant) {
        return roomSessions.active(meetingId)
                .filter(r -> r.getId().equals(participant.getRoomId()))
                .orElseThrow(() -> new ApiException(ErrorCode.MEETING_NOT_ACTIVE, "The meeting is not live."));
    }

    private static Actor actor(MeetingParticipant p, java.time.Instant since) {
        return new Actor(p.getId().toString(), p.getDisplayName(), p.getRole(), since);
    }
}
