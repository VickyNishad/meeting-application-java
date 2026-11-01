package com.example.meetplatform.identity.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_identity_links")
public class ExternalIdentityLink extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false)
    private String subject;

    @Column(length = 320)
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExternalIdentityLink() {
    }

    public ExternalIdentityLink(UUID userId, String provider, String subject, String email, Instant now) {
        this.userId = userId;
        this.provider = provider;
        this.subject = subject;
        this.email = email;
        this.createdAt = now;
    }

    public UUID getUserId() {
        return userId;
    }
}
