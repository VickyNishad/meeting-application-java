package com.example.meetplatform.recording.entity;

import com.example.meetplatform.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "recordings")
public class Recording extends AuditedEntity {

    public enum Status { STARTING, ACTIVE, STOPPING, PROCESSING, AVAILABLE, FAILED, DELETED }

    public static final Set<Status> IN_FLIGHT = EnumSet.of(Status.STARTING, Status.ACTIVE, Status.STOPPING);

    /** The whole room (composite). */
    public static final String KIND_CLOUD = "CLOUD";
    /** One participant's own camera and microphone (screen shares are only in the room recording). */
    public static final String KIND_PARTICIPANT = "PARTICIPANT";

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "egress_id", length = 64)
    private String egressId;

    @Column(nullable = false, length = 16)
    private String kind = "CLOUD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.STARTING;

    @Column(length = 32)
    private String layout;

    @Column(name = "audio_only", nullable = false)
    private boolean audioOnly;

    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "started_by")
    private UUID startedBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "retention_until")
    private Instant retentionUntil;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "participant_id")
    private UUID participantId;

    @Column(name = "subject_user_id")
    private UUID subjectUserId;

    @Column(name = "subject_name", length = 120)
    private String subjectName;

    @Column(name = "auto_started", nullable = false)
    private boolean autoStarted;

    @Column(name = "auto_suppressed", nullable = false)
    private boolean autoSuppressed;

    @Column(name = "content_type", length = 64)
    private String contentType;

    /** The meeting's visibility mode when capture started (EVERYONE or HOSTS_ONLY). */
    @Column(name = "visibility_mode", length = 24)
    private String visibilityMode;

    /** Recorded under webinar visibility: the file shows the stage only. */
    @Column(name = "stage_only", nullable = false)
    private boolean stageOnly;

    /** Creation time of the newest egress webhook applied; older events arriving late are ignored. */
    @Column(name = "last_egress_event_at")
    private Instant lastEgressEventAt;

    protected Recording() {
    }

    public Recording(UUID meetingId, UUID roomId, String layout, boolean audioOnly, UUID startedBy, Instant now) {
        this.meetingId = meetingId;
        this.roomId = roomId;
        this.layout = layout;
        this.audioOnly = audioOnly;
        this.startedBy = startedBy;
        this.startedAt = now;
    }

    /** Started by the meeting's auto-record setting rather than by a moderator; follows the active speaker. */
    public static Recording autoRoom(UUID meetingId, UUID roomId, boolean audioOnly, Instant now) {
        Recording r = new Recording(meetingId, roomId, "speaker", audioOnly, null, now);
        r.autoStarted = true;
        return r;
    }

    /** File type, and which participants the capture can show (webinar recordings show the stage only). */
    public void describeCapture(String visibilityMode, boolean stageOnly) {
        this.contentType = audioOnly && !isParticipantRecording() ? "audio/ogg" : "video/mp4";
        this.visibilityMode = visibilityMode;
        this.stageOnly = stageOnly;
    }

    /**
     * Records that an egress event created at {@code eventTime} is being applied. Returns false (and changes nothing)
     * when a newer event was already applied, so a delayed webhook cannot move the recording back.
     */
    public boolean acceptEgressEvent(Instant eventTime) {
        if (eventTime == null) {
            return true;
        }
        if (lastEgressEventAt != null && eventTime.isBefore(lastEgressEventAt)) {
            return false;
        }
        lastEgressEventAt = eventTime;
        return true;
    }

    /**
     * Status changes reported by egress only move forward: STARTING → ACTIVE → STOPPING; FAILED, AVAILABLE and
     * DELETED are final. A late "active" after a stop or failure is ignored.
     */
    public void advanceTo(Status next) {
        if (status == Status.FAILED || status == Status.AVAILABLE || status == Status.DELETED) {
            return;
        }
        if (rank(next) > rank(status)) {
            status = next;
        }
    }

    private static int rank(Status s) {
        return switch (s) {
            case STARTING -> 0;
            case ACTIVE -> 1;
            case STOPPING -> 2;
            case PROCESSING -> 3;
            case AVAILABLE, FAILED, DELETED -> 4;
        };
    }

    /** Failed captures may have left a partial file behind: they expire like completed ones. */
    public void setRetentionUntil(Instant retentionUntil) {
        this.retentionUntil = retentionUntil;
    }

    /** One participant's own media; {@code subjectUserId} is null for guests. */
    public static Recording autoParticipant(UUID meetingId, UUID roomId, UUID participantId, UUID subjectUserId,
                                            String subjectName, boolean audioOnly, Instant now) {
        Recording r = new Recording(meetingId, roomId, null, audioOnly, null, now);
        r.kind = KIND_PARTICIPANT;
        r.participantId = participantId;
        r.subjectUserId = subjectUserId;
        r.subjectName = subjectName;
        r.autoStarted = true;
        return r;
    }

    public boolean isInFlight() {
        return IN_FLIGHT.contains(status);
    }

    public boolean isParticipantRecording() {
        return KIND_PARTICIPANT.equals(kind);
    }

    /**
     * Failed, by any path (could not notify, could not start, egress failure, reconciliation). A failed capture may
     * have left a partial file: it is kept for {@link #FAILED_RETENTION} (long enough to investigate), then deleted.
     */
    public void fail(String reason, Instant now) {
        this.status = Status.FAILED;
        this.failureReason = reason == null ? null : reason.length() > 1000 ? reason.substring(0, 1000) : reason;
        if (endedAt == null) {
            endedAt = now;
        }
        if (retentionUntil == null) {
            retentionUntil = now.plus(FAILED_RETENTION);
        }
    }

    public static final java.time.Duration FAILED_RETENTION = java.time.Duration.ofDays(7);

    public void complete(Long sizeBytes, Long durationSeconds, Instant endedAt, Instant retentionUntil) {
        this.status = Status.AVAILABLE;
        this.sizeBytes = sizeBytes;
        this.durationSeconds = durationSeconds;
        this.endedAt = endedAt;
        this.retentionUntil = retentionUntil;
    }

    public void markDeleted(Instant now) {
        this.status = Status.DELETED;
        this.deletedAt = now;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public String getEgressId() {
        return egressId;
    }

    public void setEgressId(String egressId) {
        this.egressId = egressId;
    }

    public String getKind() {
        return kind;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public boolean isAudioOnly() {
        return audioOnly;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public UUID getStartedBy() {
        return startedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getRetentionUntil() {
        return retentionUntil;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public UUID getSubjectUserId() {
        return subjectUserId;
    }

    public String getSubjectName() {
        return subjectName;
    }

    public boolean isAutoStarted() {
        return autoStarted;
    }

    /** A moderator stopped this auto-started recording: auto-record must not restart it. */
    public void suppressAutoRestart() {
        this.autoSuppressed = true;
    }

    public String getLayout() {
        return layout;
    }

    public String getContentType() {
        return contentType;
    }

    public String getVisibilityMode() {
        return visibilityMode;
    }

    public boolean isStageOnly() {
        return stageOnly;
    }
}
