package com.example.meetplatform.moderation.dto;

import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaPermission;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.entity.MeetingEnums.ScreenSharePolicy;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class ModerationDtos {

    private ModerationDtos() {
    }

    public enum MuteSource { MICROPHONE, CAMERA, SCREEN_SHARE }

    public enum UnmuteSource { MICROPHONE, CAMERA }

    public record MuteRequest(@NotNull MuteSource source) {
    }

    public record RequestUnmuteRequest(@NotNull UnmuteSource source) {
    }

    public record RoleRequest(@NotNull MeetingRole role) {
    }

    public record LockRequest(@NotNull Boolean locked) {
    }

    public record SettingsRequest(ScreenSharePolicy screenSharePolicy, Boolean waitingRoomEnabled, Boolean chatEnabled) {
    }

    /** Meeting-wide media permissions to change (null = leave as is). Host only. */
    public record MediaPermissionsRequest(MediaPermission microphone, MediaPermission camera, MediaPermission screenShare) {
    }

    /** ALLOWED / RESTRICTED: override the meeting's permission for this participant; DEFAULT: remove the override. */
    public enum MediaOverride { ALLOWED, RESTRICTED, DEFAULT }

    /** One participant's media overrides to change (null = leave as is). Host only. */
    public record ParticipantMediaRequest(MediaOverride microphone, MediaOverride camera, MediaOverride screenShare) {
    }

    public record AuditEntry(UUID id, String action, UserSummary actor, UUID actorParticipantId, String details,
                             Instant createdAt) {
    }
}
