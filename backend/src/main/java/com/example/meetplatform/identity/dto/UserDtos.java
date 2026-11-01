package com.example.meetplatform.identity.dto;

import com.example.meetplatform.common.validation.ValidTimeZone;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class UserDtos {

    private UserDtos() {
    }

    /** {@code email} is null where the viewer may not see it (e.g. the host shown to guests joining a meeting). */
    public record UserSummary(UUID id, String displayName, String email, String avatarUrl) {

        public UserSummary withoutEmail() {
            return new UserSummary(id, displayName, null, avatarUrl);
        }
    }

    public record UserProfile(UUID id, String displayName, String email, String avatarUrl, String timeZone,
                              boolean emailVerified, String systemRole, String status, Instant createdAt) {
    }

    public record UpdateProfileRequest(
            @Size(min = 1, max = 120) String displayName,
            @ValidTimeZone String timeZone,
            @Size(max = 1024) @Pattern(regexp = "^https://.*", message = "must be an https URL") String avatarUrl) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {
    }

    public record SessionView(UUID id, Instant createdAt, Instant lastUsedAt, String userAgent, String ipAddress,
                              boolean current) {
    }
}
