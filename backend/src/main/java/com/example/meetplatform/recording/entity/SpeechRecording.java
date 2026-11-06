package com.example.meetplatform.recording.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One speaking turn of one participant, recorded by that participant's own browser and filed by the server (see
 * SpeechRecordingService). {@code storagePath} is relative to the storage root and is never sent to clients.
 */
@Entity
@Table(name = "speech_recordings")
public class SpeechRecording extends BaseEntity {

    /**
     * RECORDING: chunks are arriving. COMPLETED: the file was finalized and verified. INCOMPLETE: recording stopped
     * without a proper finish (client gone, server restart); what arrived is kept. FAILED: nothing usable was stored.
     */
    public enum Status { RECORDING, COMPLETED, INCOMPLETE, FAILED }

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "participant_user_id")
    private UUID participantUserId;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "meeting_title", nullable = false, length = 200)
    private String meetingTitle;

    @Column(name = "participant_name", nullable = false, length = 120)
    private String participantName;

    @Column(name = "file_name", nullable = false, length = 64)
    private String fileName;

    @Column(name = "storage_path", nullable = false, length = 600)
    private String storagePath;

    @Column(name = "file_format", nullable = false, length = 64)
    private String fileFormat;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RECORDING;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "chunks_received", nullable = false)
    private int chunksReceived;

    /** Set once the clip's object is gone (retention): the row stays as a record, the clip is no longer listed or played. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "last_chunk_at")
    private Instant lastChunkAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SpeechRecording() {
    }

    public SpeechRecording(UUID id, UUID meetingId, UUID roomId, UUID participantId, UUID participantUserId, UUID hostId,
                           String meetingTitle, String participantName, String fileName, String storagePath,
                           String fileFormat, Instant startTime) {
        super(id);
        this.meetingId = meetingId;
        this.roomId = roomId;
        this.participantId = participantId;
        this.participantUserId = participantUserId;
        this.hostId = hostId;
        this.meetingTitle = meetingTitle;
        this.participantName = participantName;
        this.fileName = fileName;
        this.storagePath = storagePath;
        this.fileFormat = fileFormat;
        this.startTime = startTime;
        this.lastChunkAt = startTime;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void markDeleted(Instant now) {
        deletedAt = now;
        updatedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void chunkStored(long newSize, Instant now) {
        chunksReceived++;
        fileSize = newSize;
        lastChunkAt = now;
    }

    public void complete(long size, Instant end, long durationMillis) {
        status = Status.COMPLETED;
        fileSize = size;
        endTime = end;
        durationMs = durationMillis;
        failureReason = null;
    }

    public void incomplete(long size, Instant end, String reason) {
        status = Status.INCOMPLETE;
        fileSize = size;
        endTime = end;
        durationMs = end == null ? null : Math.max(0, end.toEpochMilli() - startTime.toEpochMilli());
        failureReason = reason;
    }

    public void fail(Instant end, String reason) {
        status = Status.FAILED;
        endTime = end;
        failureReason = reason;
    }

    public boolean isRecording() {
        return status == Status.RECORDING;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public UUID getParticipantUserId() {
        return participantUserId;
    }

    public UUID getHostId() {
        return hostId;
    }

    public String getMeetingTitle() {
        return meetingTitle;
    }

    public String getParticipantName() {
        return participantName;
    }

    public String getFileName() {
        return fileName;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getFileFormat() {
        return fileFormat;
    }

    public long getFileSize() {
        return fileSize;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getChunksReceived() {
        return chunksReceived;
    }

    public Instant getLastChunkAt() {
        return lastChunkAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
