package com.example.meetplatform.room.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** One live session of a meeting, backed by exactly one media-server room. */
@Entity
@Table(name = "meeting_rooms")
public class MeetingRoom extends BaseEntity {

    public enum Status { ACTIVE, ENDED }

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "livekit_room_name", nullable = false, length = 128)
    private String livekitRoomName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "peak_participants", nullable = false)
    private int peakParticipants;

    @Version
    private Long version;

    protected MeetingRoom() {
    }

    public MeetingRoom(UUID meetingId, String livekitRoomName, Instant startedAt) {
        this.meetingId = meetingId;
        this.livekitRoomName = livekitRoomName;
        this.startedAt = startedAt;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public void end(Instant now) {
        status = Status.ENDED;
        endedAt = now;
    }

    public void observeParticipants(int count) {
        peakParticipants = Math.max(peakParticipants, count);
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public String getLivekitRoomName() {
        return livekitRoomName;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public int getPeakParticipants() {
        return peakParticipants;
    }
}
