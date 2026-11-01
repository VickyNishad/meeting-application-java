package com.example.meetplatform.identity.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A login session represented by a rotating opaque refresh token. Only SHA-256 hashes are stored.
 * The previous hash is kept to detect refresh-token reuse (a stolen token being replayed).
 */
@Entity
@Table(name = "user_sessions")
public class UserSession extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    private String refreshTokenHash;

    @Column(name = "previous_token_hash", length = 64)
    private String previousTokenHash;

    @Column(name = "client_type", nullable = false, length = 16)
    private String clientType;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 64)
    private String revokeReason;

    protected UserSession() {
    }

    public UserSession(UUID userId, String refreshTokenHash, String clientType, String userAgent, String ipAddress,
                       Instant now, Instant expiresAt) {
        this.userId = userId;
        this.refreshTokenHash = refreshTokenHash;
        this.clientType = clientType;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
        this.createdAt = now;
        this.lastUsedAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void rotate(String newHash, Instant now, String ipAddress, String userAgent) {
        this.previousTokenHash = this.refreshTokenHash;
        this.refreshTokenHash = newHash;
        this.lastUsedAt = now;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public void revoke(Instant now, String reason) {
        if (revokedAt == null) {
            revokedAt = now;
            revokeReason = reason;
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public String getClientType() {
        return clientType;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
