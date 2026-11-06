package com.example.meetplatform.file.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_metadata")
public class FileMetadata extends BaseEntity {

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "uploaded_by_participant_id", nullable = false)
    private UUID uploadedByParticipantId;

    @Column(name = "uploaded_by_name", nullable = false, length = 120)
    private String uploadedByName;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 127)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected FileMetadata() {
    }

    public FileMetadata(UUID id, UUID meetingId, UUID uploadedByParticipantId, String uploadedByName, String fileName,
                        String contentType, long sizeBytes, String sha256, String storageKey, Instant createdAt) {
        super(id);
        this.meetingId = meetingId;
        this.uploadedByParticipantId = uploadedByParticipantId;
        this.uploadedByName = uploadedByName;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.createdAt = createdAt;
    }

    public UUID getUploadedByParticipantId() {
        return uploadedByParticipantId;
    }

    public void markDeleted(Instant now) {
        deletedAt = now;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public String getUploadedByName() {
        return uploadedByName;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
