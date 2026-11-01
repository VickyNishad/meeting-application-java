package com.example.meetplatform.calendar.entity;

import com.example.meetplatform.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A user's link to an external calendar. OAuth tokens are stored encrypted (AES-GCM), never in clear. */
@Entity
@Table(name = "calendar_connections")
public class CalendarConnection extends AuditedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "external_account_email", length = 320)
    private String externalAccountEmail;

    @Column(name = "access_token_encrypted")
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted")
    private String refreshTokenEncrypted;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(length = 1000)
    private String scopes;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    protected CalendarConnection() {
    }

    public CalendarConnection(UUID userId, String provider) {
        this.userId = userId;
        this.provider = provider;
    }

    public void updateTokens(String accessEncrypted, String refreshEncrypted, Instant expiresAt) {
        this.accessTokenEncrypted = accessEncrypted;
        if (refreshEncrypted != null) {
            this.refreshTokenEncrypted = refreshEncrypted;
        }
        this.tokenExpiresAt = expiresAt;
        this.status = "ACTIVE";
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }

    public String getExternalAccountEmail() {
        return externalAccountEmail;
    }

    public void setExternalAccountEmail(String externalAccountEmail) {
        this.externalAccountEmail = externalAccountEmail;
    }

    public String getAccessTokenEncrypted() {
        return accessTokenEncrypted;
    }

    public String getRefreshTokenEncrypted() {
        return refreshTokenEncrypted;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
