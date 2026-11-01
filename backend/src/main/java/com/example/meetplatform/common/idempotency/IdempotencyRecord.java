package com.example.meetplatform.common.idempotency;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** A used {@code Idempotency-Key}: reserved (no response yet) while the first request runs, then completed. */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String scope;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    /** Reserves the key for an in-flight request; {@code expiresAt} bounds how long a crashed request blocks it. */
    public IdempotencyRecord(String scope, String idempotencyKey, String requestHash, Instant createdAt,
                             Instant expiresAt) {
        this.scope = scope;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void complete(int responseStatus, String responseBody, Instant expiresAt) {
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.expiresAt = expiresAt;
    }

    public boolean isCompleted() {
        return responseStatus != null;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
