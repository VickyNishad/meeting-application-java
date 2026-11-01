package com.example.meetplatform.chat.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_messages")
public class ChatMessage extends BaseEntity {

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "participant_id", nullable = false)
    private UUID participantId;

    @Column(name = "sender_name", nullable = false, length = 120)
    private String senderName;

    @Column(nullable = false, length = 4000)
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** ALL: everyone in the meeting. STAGE: an attendee's message in a webinar, for the stage and the sender only. */
    @Column(nullable = false, length = 8)
    private String audience = AUDIENCE_ALL;

    public static final String AUDIENCE_ALL = "ALL";
    public static final String AUDIENCE_STAGE = "STAGE";

    protected ChatMessage() {
    }

    public ChatMessage(UUID meetingId, UUID roomId, UUID participantId, String senderName, String text, Instant createdAt) {
        this.meetingId = meetingId;
        this.roomId = roomId;
        this.participantId = participantId;
        this.senderName = senderName;
        this.text = text;
        this.createdAt = createdAt;
    }

    public ChatMessage(UUID meetingId, UUID roomId, UUID participantId, String senderName, String text, Instant createdAt,
                       String audience) {
        this(meetingId, roomId, participantId, senderName, text, createdAt);
        this.audience = audience;
    }

    public String getAudience() {
        return audience;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
