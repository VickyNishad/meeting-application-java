package com.example.meetplatform.participant.service;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.media.dto.MediaPermissions;
import com.example.meetplatform.media.dto.MediaTokenRequest;
import com.example.meetplatform.media.dto.TrackSource;
import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.media.service.RoomNames;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaMode;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaKind;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaPermission;
import com.example.meetplatform.meeting.service.MeetingPermissions;
import com.example.meetplatform.participant.dto.RoomStateDtos.MediaPermissionsView;
import com.example.meetplatform.participant.dto.RoomStateDtos.ParticipantMedia;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Derives media permissions and token metadata from the server-side role. Clients never choose these.
 *
 * <p>No participant may publish data-channel messages: every message on the room's topics (moderation, recording,
 * chat, hands, reactions) is relayed by the server, so clients can trust that anything they receive is server-sent.
 *
 * <p>In a HOST_AND_SELF_ONLY session attendees appear to everyone else in the SFU only as "Attendee" with no user
 * id; the stage gets their real names from the REST participant list.
 */
@Component
public class ParticipantPolicy {

    public static final String ATTENDEE_NAME = "Attendee";

    private final MeetingPermissions permissions;
    private final MediaService media;
    private final JsonMapper jsonMapper;
    private final AppProperties.LiveKit props;

    public ParticipantPolicy(MeetingPermissions permissions, MediaService media, JsonMapper jsonMapper,
                             AppProperties properties) {
        this.permissions = permissions;
        this.media = media;
        this.jsonMapper = jsonMapper;
        this.props = properties.livekit();
    }

    /**
     * Permissions in the session's stage room. In HOST_AND_SELF_ONLY an attendee only watches the stage there: it may
     * subscribe, may not publish, and is hidden from everyone else in that room.
     */
    public MediaPermissions permissionsFor(Meeting meeting, MeetingParticipant participant) {
        if (meeting.isStageOnly() && !participant.getRole().isModerator()) {
            return new MediaPermissions(true, false, EnumSet.noneOf(TrackSource.class), true);
        }
        return new MediaPermissions(true, false, publishSources(meeting, participant));
    }

    /**
     * Permissions in the audience room of a HOST_AND_SELF_ONLY session: attendees publish there but may not subscribe
     * to anything, so no attendee can receive another attendee's media; the stage subscribes (hidden, publishing
     * nothing there) and so sees and hears every attendee.
     */
    public MediaPermissions audiencePermissionsFor(Meeting meeting, MeetingParticipant participant) {
        if (participant.getRole().isModerator()) {
            return new MediaPermissions(true, false, EnumSet.noneOf(TrackSource.class), true);
        }
        return new MediaPermissions(false, false, publishSources(meeting, participant));
    }

    /**
     * Whether the participant may publish this medium, the one rule behind every token and live grant update:
     * <ol>
     *   <li>camera and screen sharing exist only in video meetings;</li>
     *   <li>hosts and co-hosts always may (screen sharing also: any role granted MEETING_SCREEN_SHARE);</li>
     *   <li>otherwise the host's override for this participant, if any;</li>
     *   <li>otherwise the meeting's permission; anything missing counts as restricted.</li>
     * </ol>
     */
    public boolean mayPublish(Meeting meeting, MeetingParticipant participant, MediaKind kind) {
        if (kind != MediaKind.MICROPHONE && meeting.getMediaMode() != MediaMode.VIDEO) {
            return false;
        }
        MeetingRole role = participant.getRole();
        if (role.isModerator() || (kind == MediaKind.SCREEN_SHARE && permissions.has(role, MeetingPermissions.SCREEN_SHARE))) {
            return true;
        }
        MediaPermission override = participant.getMediaOverride(kind);
        MediaPermission effective = override != null ? override : meeting.getMediaPermission(kind);
        return effective == MediaPermission.ALLOWED;
    }

    public MediaPermissionsView mediaPermissions(Meeting meeting) {
        return new MediaPermissionsView(meeting.getMediaPermission(MediaKind.MICROPHONE),
                meeting.getMediaPermission(MediaKind.CAMERA), meeting.getMediaPermission(MediaKind.SCREEN_SHARE));
    }

    public ParticipantMedia participantMedia(Meeting meeting, MeetingParticipant participant) {
        return new ParticipantMedia(participant.getId().toString(), participant.getMediaOverride(MediaKind.MICROPHONE),
                participant.getMediaOverride(MediaKind.CAMERA), participant.getMediaOverride(MediaKind.SCREEN_SHARE),
                mayPublish(meeting, participant, MediaKind.MICROPHONE), mayPublish(meeting, participant, MediaKind.CAMERA),
                mayPublish(meeting, participant, MediaKind.SCREEN_SHARE));
    }

    private Set<TrackSource> publishSources(Meeting meeting, MeetingParticipant participant) {
        Set<TrackSource> sources = EnumSet.noneOf(TrackSource.class);
        boolean microphone = mayPublish(meeting, participant, MediaKind.MICROPHONE);
        if (microphone) {
            sources.add(TrackSource.MICROPHONE);
        }
        if (mayPublish(meeting, participant, MediaKind.CAMERA)) {
            sources.add(TrackSource.CAMERA);
        }
        if (mayPublish(meeting, participant, MediaKind.SCREEN_SHARE)) {
            sources.add(TrackSource.SCREEN_SHARE);
            // The SFU cannot tell what an audio track carries: screen-share audio could be a microphone in disguise, so
            // it also needs the microphone permission.
            if (microphone) {
                sources.add(TrackSource.SCREEN_SHARE_AUDIO);
            }
        }
        return sources;
    }

    /**
     * Join credentials: the stage room token, plus (attendee of a HOST_AND_SELF_ONLY session) its own audience room
     * and token. The stage gets the attendees' audience rooms from {@link #audienceRooms}.
     */
    public record MediaAccess(String token, String audienceRoomName, String audienceToken) {
    }

    /** Credentials for one attendee's audience room. */
    public record AudienceRoom(UUID attendeeId, String roomName, String token) {
    }

    public MediaAccess issueAccess(Meeting meeting, MeetingParticipant participant, String roomName) {
        String stage = issueToken(meeting, participant, roomName);
        if (!isMasked(meeting, participant.getRole())) {
            return new MediaAccess(stage, null, null);
        }
        AudienceRoom own = ownAudienceRoom(meeting, participant, roomName);
        return new MediaAccess(stage, own.roomName(), own.token());
    }

    /**
     * The audience rooms a participant of a HOST_AND_SELF_ONLY session may connect to: its own (attendee, publishing,
     * created here if needed), or every present attendee's (host and co-hosts, hidden and subscribing). Empty in any
     * other session.
     */
    public List<AudienceRoom> audienceRooms(Meeting meeting, MeetingParticipant viewer, String stageRoom,
                                            List<MeetingParticipant> present) {
        if (!meeting.isStageOnly()) {
            return List.of();
        }
        if (!viewer.getRole().isModerator()) {
            return List.of(ownAudienceRoom(meeting, viewer, stageRoom));
        }
        String metadata = metadataFor(meeting, viewer);
        MediaPermissions watcher = audiencePermissionsFor(meeting, viewer);
        return present.stream()
                .filter(p -> isMasked(meeting, p.getRole()))
                .map(p -> {
                    String room = RoomNames.audience(stageRoom, p.getId());
                    return new AudienceRoom(p.getId(), room, media.issueToken(new MediaTokenRequest(room,
                            viewer.getId().toString(), viewer.getDisplayName(), metadata, watcher, props.tokenTtl())));
                })
                .toList();
    }

    private AudienceRoom ownAudienceRoom(Meeting meeting, MeetingParticipant attendee, String stageRoom) {
        String room = RoomNames.audience(stageRoom, attendee.getId());
        // Idempotent on the SFU: a rejoin or reload finds the room it already has.
        media.createRoom(room, 1);
        String token = media.issueToken(new MediaTokenRequest(room, attendee.getId().toString(),
                visibleName(meeting, attendee), metadataFor(meeting, attendee),
                audiencePermissionsFor(meeting, attendee), props.tokenTtl()));
        return new AudienceRoom(attendee.getId(), room, token);
    }

    /** Whether this participant's identity is hidden from everyone but the stage (a webinar attendee). */
    public static boolean isMasked(Meeting meeting, MeetingRole role) {
        return meeting.isStageOnly() && !role.isModerator();
    }

    /** The name other participants see in the SFU. */
    public String visibleName(Meeting meeting, MeetingParticipant participant) {
        return isMasked(meeting, participant.getRole()) ? ATTENDEE_NAME : participant.getDisplayName();
    }

    public String metadataFor(Meeting meeting, MeetingParticipant participant) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("role", participant.getRole().name());
        if (participant.getUserId() != null && !isMasked(meeting, participant.getRole())) {
            metadata.put("userId", participant.getUserId().toString());
        }
        return jsonMapper.writeValueAsString(metadata);
    }

    public String issueToken(Meeting meeting, MeetingParticipant participant, String roomName) {
        return media.issueToken(new MediaTokenRequest(roomName, participant.getId().toString(),
                visibleName(meeting, participant), metadataFor(meeting, participant),
                permissionsFor(meeting, participant), props.tokenTtl()));
    }
}
