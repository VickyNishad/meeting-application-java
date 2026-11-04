package com.example.meetplatform.participant.dto;

import com.example.meetplatform.meeting.entity.MeetingEnums.MediaPermission;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.entity.MeetingEnums.ScreenSharePolicy;
import com.example.meetplatform.meeting.entity.MeetingEnums.VisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RoomStateDtos {

    private RoomStateDtos() {
    }

    /**
     * Everything a client shows about the live session, from the server's point of view. Clients load it on connect,
     * after a reconnect, and whenever the {@code seq} of a server signal shows they missed one (or its {@code epoch}
     * differs from this one: the counters were reset): signals with
     * {@code stream=all} continue {@code seq}, signals with {@code stream=stage} continue {@code stageSeq} (stage only).
     *
     * <p>{@code stageIdentities}: media identities of the host and co-hosts present. In a webinar attendees publish
     * only to them. {@code participants}: real names of everyone present, for the stage of a webinar only (everyone
     * else gets null, as the SFU shows attendees as "Attendee"). {@code presentCount}: people in the session, one per
     * person (a HOST_AND_SELF_ONLY attendee counts only the stage and itself).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoomStateView(String epoch, long seq, Long stageSeq, UUID participantId, MeetingRole role,
                                VisibilityMode visibilityMode, List<String> stageIdentities, boolean locked,
                                boolean chatEnabled, ScreenSharePolicy screenSharePolicy, boolean waitingRoomEnabled,
                                boolean cloudRecording, boolean autoRecord, List<Actor> localRecorders,
                                List<Actor> raisedHands, Integer waitingCount, List<Actor> participants,
                                int presentCount, MediaPermissionsView mediaPermissions, ParticipantMedia myMedia,
                                List<ParticipantMedia> participantMedia) {
    }

    /** The meeting-wide media permissions for participants (hosts and co-hosts may always publish). */
    public record MediaPermissionsView(MediaPermission microphone, MediaPermission camera, MediaPermission screenShare) {
    }

    /**
     * One participant's media rights: the host's overrides (null = follows the meeting) and what it may publish now.
     * Everyone gets their own ({@code myMedia}); only the host gets everyone's ({@code participantMedia}).
     */
    public record ParticipantMedia(String participantId, MediaPermission microphoneOverride, MediaPermission cameraOverride,
                                   MediaPermission screenShareOverride, boolean microphone, boolean camera,
                                   boolean screenShare) {
    }

    /** A participant doing something (recording locally, hand raised) since {@code since}. */
    public record Actor(String participantId, String displayName, MeetingRole role, Instant since) {
    }

    public record HandRequest(@NotNull UUID participantId, @NotNull Boolean raised) {
    }

    /**
     * The audience rooms of a HOST_AND_SELF_ONLY session this participant may connect to now: an attendee its own
     * (publishing), the stage every present attendee's (hidden, subscribing). Empty in any other session.
     */
    public record AudienceAccessView(String livekitUrl, List<AudienceRoomView> rooms) {
    }

    public record AudienceRoomView(UUID participantId, String roomName, String token) {
    }

    public record ReactionRequest(@NotNull UUID participantId, @NotBlank @Size(max = 16) String emoji) {
    }
}
