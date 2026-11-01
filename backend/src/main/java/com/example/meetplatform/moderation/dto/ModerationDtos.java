package com.example.meetplatform.moderation.dto;

import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
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

    public record AuditEntry(UUID id, String action, UserSummary actor, UUID actorParticipantId, String details,
                             Instant createdAt) {
    }
}
