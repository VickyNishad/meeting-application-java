package com.example.meetplatform.participant.dto;

import com.example.meetplatform.meeting.dto.MeetingDtos.MeetingView;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ParticipantDtos {

    private ParticipantDtos() {
    }

    public enum JoinStatus { ADMITTED, WAITING, DENIED }

    /**
     * {@code rejoin}: a guest joining the meeting again from the same browser proves its earlier participation (id and
     * participant secret), so the host's media restrictions on it still apply. Ignored for signed-in users.
     */
    public record JoinRequest(@Size(max = 64) String password, @Size(min = 1, max = 120) String displayName,
                              @Valid Rejoin rejoin) {
    }

    public record Rejoin(@NotNull UUID participantId, @NotNull @Size(max = 128) String token) {
    }

    /**
     * {@code token}/{@code roomName}: the session's stage room. In a HOST_AND_SELF_ONLY session the client also joins
     * {@code audienceRoomName} with {@code audienceToken}: attendees publish their camera and microphone there (and
     * cannot subscribe), the host and co-hosts subscribe there to see and hear attendees.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JoinResponse(JoinStatus status, UUID participantId, String waitToken, MeetingRole role,
                               String livekitUrl, String token, String roomName, String waitingReason,
                               MeetingView meeting, String audienceRoomName, String audienceToken) {

        public JoinResponse(JoinStatus status, UUID participantId, String waitToken, MeetingRole role,
                            String livekitUrl, String token, String roomName, String waitingReason,
                            MeetingView meeting) {
            this(status, participantId, waitToken, role, livekitUrl, token, roomName, waitingReason, meeting, null, null);
        }
    }

    public record LeaveRequest(@NotNull UUID participantId) {
    }

    public record ParticipantView(UUID id, UUID userId, String displayName, MeetingRole role,
                                  MeetingParticipant.State state, Instant joinedAt, Instant leftAt,
                                  Long durationSeconds) {

        public static ParticipantView of(MeetingParticipant p) {
            return new ParticipantView(p.getId(), p.getUserId(), p.getDisplayName(), p.getRole(), p.getState(),
                    p.getJoinedAt(), p.getLeftAt(), p.durationSeconds());
        }
    }
}
