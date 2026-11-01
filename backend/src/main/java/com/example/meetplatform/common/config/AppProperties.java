package com.example.meetplatform.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Typed application configuration. Every secret is supplied through environment variables
 * (see {@code .env.example}); nothing secret has a usable default.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String publicUrl,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Security security,
        @Valid @NotNull Auth auth,
        @Valid @NotNull LiveKit livekit,
        @Valid @NotNull Storage storage,
        @Valid @NotNull Recording recording,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Notifications notifications,
        @Valid @NotNull Files files,
        @Valid @NotNull Meetings meetings) {

    public record Cors(@NotNull List<String> allowedOrigins) {
    }

    public record Security(
            @NotBlank String jwtSecret,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl,
            boolean cookieSecure,
            @NotBlank String issuer,
            // HTTP Basic password for user "prometheus" to scrape /actuator/prometheus; blank = endpoint closed
            String metricsScrapePassword) {
    }

    public record Auth(
            boolean requireEmailVerification,
            @NotNull Duration emailVerificationTtl,
            @NotNull Duration passwordResetTtl,
            @Min(1) int maxFailedLogins,
            @NotNull Duration lockoutDuration) {
    }

    public record LiveKit(
            @NotBlank String publicUrl,
            @NotBlank String apiUrl,
            @NotBlank String apiKey,
            @NotBlank String apiSecret,
            @NotNull Duration tokenTtl,
            @Min(10) int emptyRoomTimeoutSeconds,
            @NotNull Duration requestTimeout) {
    }

    public record Storage(
            @NotBlank String endpoint,
            @NotBlank String publicEndpoint,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            @NotBlank String region,
            @NotBlank String recordingsBucket,
            @NotBlank String filesBucket,
            @NotNull Duration presignTtl,
            // "s3" (default) or "local": the "dev" profile keeps objects on this machine's disk under localRoot
            @NotBlank String type,
            String localRoot) {
    }

    public record Recording(@Min(1) int defaultRetentionDays, @NotBlank String filePrefix) {
    }

    public record RateLimit(boolean enabled) {
    }

    public record Notifications(
            boolean dispatchEnabled,
            @NotBlank String fromAddress,
            @Min(1) int maxAttempts,
            @NotNull List<Duration> reminderOffsets) {
    }

    public record Files(@Min(1) long maxSizeBytes, @NotNull List<String> allowedContentTypes) {
    }

    public record Meetings(@Min(2) int defaultMaxParticipants, @Min(2) int hardMaxParticipants) {
    }
}
