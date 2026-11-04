package com.example.meetplatform.participant.entity;

import com.example.meetplatform.common.persistence.AuditedEntity;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaKind;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaPermission;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One attempt by a person (account or guest) to take part in a meeting session. Its id is the media-server
 * identity, so the server can address and moderate exactly this connection.
 */
@Entity
@Table(name = "meeting_participants")
public class MeetingParticipant extends AuditedEntity {

    public enum State { WAITING, ADMITTED, JOINED, LEFT, REMOVED, DENIED }

    public static final Set<State> PRESENT = EnumSet.of(State.ADMITTED, State.JOINED);
    public static final Set<State> OPEN = EnumSet.of(State.WAITING, State.ADMITTED, State.JOINED);

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MeetingRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;

    @Column(name = "wait_token_hash", nullable = false, length = 64)
    private String waitTokenHash;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "admitted_at")
    private Instant admittedAt;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Column(name = "media_sid", length = 64)
    private String mediaSid;

    @Column(name = "local_recording_started_at")
    private Instant localRecordingStartedAt;

    @Column(name = "hand_raised_at")
    private Instant handRaisedAt;

    /** Webinar attendee: hidden from other attendees, whatever the meeting's visibility mode becomes later. */
    @Column(nullable = false)
    private boolean masked;

    /** The host's per-participant media overrides; null = follow the meeting's permission. */
    @Enumerated(EnumType.STRING)
    @Column(name = "mic_override", length = 16)
    private MediaPermission micOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "camera_override", length = 16)
    private MediaPermission cameraOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "screen_override", length = 16)
    private MediaPermission screenOverride;

    protected MeetingParticipant() {
    }

    public MeetingParticipant(UUID meetingId, UUID roomId, UUID userId, String displayName, MeetingRole role,
                              String waitTokenHash, String ipAddress) {
        this.meetingId = meetingId;
        this.roomId = roomId;
        this.userId = userId;
        this.displayName = displayName;
        this.role = role;
        this.state = State.WAITING;
        this.waitTokenHash = waitTokenHash;
        this.ipAddress = ipAddress;
    }

    public boolean isPresent() {
        return PRESENT.contains(state);
    }

    public boolean isOpen() {
        return OPEN.contains(state);
    }

    /**
     * Was actually let into a session and not thrown out: only such participations grant lasting read access
     * (meeting details, chat history, files, recordings). WAITING, DENIED and REMOVED never do, and a LEFT row
     * only counts when it had been admitted (a cancelled knock also ends as LEFT).
     */
    public boolean wasAdmitted() {
        return admittedAt != null && (state == State.ADMITTED || state == State.JOINED || state == State.LEFT);
    }

    public void admit(UUID roomId, Instant now) {
        this.roomId = roomId;
        this.state = State.ADMITTED;
        this.admittedAt = now;
    }

    public void markJoined(Instant now) {
        this.state = State.JOINED;
        if (joinedAt == null) {
            joinedAt = now;
        }
        leftAt = null;
    }

    /** Joined through this SFU connection; a later disconnect of an older connection must not end this one. */
    public void markJoined(Instant now, String connectionSid) {
        markJoined(now);
        if (connectionSid != null) {
            this.mediaSid = connectionSid;
        }
    }

    /**
     * True when a disconnect reported for {@code connectionSid} belongs to an older connection than the one this
     * participant joined with last (the participant reconnected and the old connection's event arrived late).
     */
    public boolean isStaleConnection(String connectionSid) {
        return connectionSid != null && mediaSid != null && !mediaSid.equals(connectionSid);
    }

    /**
     * True when {@code connectionSid} is the connection this participant had when it left: a join event for it that
     * arrives after the leave describes a connection that is already over.
     */
    public boolean isEndedConnection(String connectionSid) {
        return state == State.LEFT && connectionSid != null && connectionSid.equals(mediaSid);
    }

    /** Left (not removed) at most {@code window} ago, after having been admitted: a reload or reconnect, not a new entry. */
    public boolean leftWithin(Instant now, java.time.Duration window) {
        return state == State.LEFT && admittedAt != null && leftAt != null && !leftAt.isBefore(now.minus(window));
    }

    public void markLeft(Instant now) {
        if (isOpen()) {
            this.state = State.LEFT;
            this.leftAt = now;
            clearLiveState();
        }
    }

    public void remove(Instant now) {
        this.state = State.REMOVED;
        this.leftAt = now;
        clearLiveState();
    }

    /** Hand and local-recording flags describe a live connection only. */
    private void clearLiveState() {
        this.handRaisedAt = null;
        this.localRecordingStartedAt = null;
    }

    public boolean isRecordingLocally() {
        return localRecordingStartedAt != null;
    }

    public void setLocalRecording(boolean recording, Instant now) {
        this.localRecordingStartedAt = recording ? (localRecordingStartedAt == null ? now : localRecordingStartedAt) : null;
    }

    public boolean isHandRaised() {
        return handRaisedAt != null;
    }

    public void setHandRaised(boolean raised, Instant now) {
        this.handRaisedAt = raised ? (handRaisedAt == null ? now : handRaisedAt) : null;
    }

    public Instant getHandRaisedAt() {
        return handRaisedAt;
    }

    public boolean isMasked() {
        return masked;
    }

    public void setMasked(boolean masked) {
        this.masked = masked;
    }

    /** The host's override for one medium, or null when the participant follows the meeting's permission. */
    public MediaPermission getMediaOverride(MediaKind kind) {
        return switch (kind) {
            case MICROPHONE -> micOverride;
            case CAMERA -> cameraOverride;
            case SCREEN_SHARE -> screenOverride;
        };
    }

    public void setMediaOverride(MediaKind kind, MediaPermission override) {
        switch (kind) {
            case MICROPHONE -> micOverride = override;
            case CAMERA -> cameraOverride = override;
            case SCREEN_SHARE -> screenOverride = override;
        }
    }

    public void deny(Instant now) {
        this.state = State.DENIED;
        this.leftAt = now;
    }

    public Long durationSeconds() {
        if (joinedAt == null) {
            return null;
        }
        Instant end = leftAt != null ? leftAt : Instant.now();
        return Duration.between(joinedAt, end).toSeconds();
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public void setRoomId(UUID roomId) {
        this.roomId = roomId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public MeetingRole getRole() {
        return role;
    }

    public void setRole(MeetingRole role) {
        this.role = role;
    }

    public State getState() {
        return state;
    }

    public String getWaitTokenHash() {
        return waitTokenHash;
    }

    public void setWaitTokenHash(String waitTokenHash) {
        this.waitTokenHash = waitTokenHash;
    }

    public Instant getAdmittedAt() {
        return admittedAt;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getLeftAt() {
        return leftAt;
    }
}
