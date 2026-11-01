package com.example.meetplatform.identity.dto;

import com.example.meetplatform.common.validation.ValidTimeZone;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public enum ClientType { WEB, NATIVE }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 120) String displayName,
            @NotNull @ValidTimeZone String timeZone) {
    }

    public record RegisterResponse(UUID userId, boolean emailVerificationRequired) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 128) String password,
            ClientType clientType) {
    }

    public record RefreshRequest(@Size(max = 256) String refreshToken) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuthResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
                               UserDtos.UserProfile user) {
    }

    public record TokenRequest(@NotBlank @Size(max = 256) String token) {
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 320) String email) {
    }

    public record PasswordResetConfirmRequest(
            @NotBlank @Size(max = 256) String token,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {
    }

    public record OAuthProvider(String id, String authorizationUrl) {
    }
}
