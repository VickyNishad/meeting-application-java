package com.example.meetplatform.recording.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recording_access_logs")
public class RecordingAccessLog extends BaseEntity {

    @Column(name = "recording_id", nullable = false)
    private UUID recordingId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "access_type", nullable = false, length = 16)
    private String accessType;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RecordingAccessLog() {
    }

    public RecordingAccessLog(UUID recordingId, UUID userId, String accessType, String ipAddress, String userAgent,
                              Instant createdAt) {
        this.recordingId = recordingId;
        this.userId = userId;
        this.accessType = accessType;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.createdAt = createdAt;
    }
}
