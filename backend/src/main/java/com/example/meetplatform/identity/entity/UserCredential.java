package com.example.meetplatform.identity.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Password credential. Its id is the owning user's id. */
@Entity
@Table(name = "user_credentials")
@AttributeOverride(name = "id", column = @Column(name = "user_id"))
public class UserCredential extends BaseEntity {

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected UserCredential() {
    }

    public UserCredential(UUID userId, String passwordHash, Instant now) {
        super(userId);
        this.passwordHash = passwordHash;
        this.passwordChangedAt = now;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void recordFailure(int maxAttempts, Instant lockUntil) {
        failedAttempts++;
        if (failedAttempts >= maxAttempts) {
            lockedUntil = lockUntil;
            failedAttempts = 0;
        }
    }

    public void recordSuccess() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    public void changePassword(String newHash, Instant now) {
        this.passwordHash = newHash;
        this.passwordChangedAt = now;
        recordSuccess();
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }
}
