package com.example.meetplatform.media.dto;

import java.time.Instant;

/**
 * Provider-neutral view of a verified media-server webhook. {@code participantSid} identifies the participant's SFU
 * connection (a reconnect gets a new one), so events of an older connection can be told apart from the current one.
 */
public record MediaWebhookEvent(String id, Type type, String rawType, String roomName, String participantIdentity,
                                EgressUpdate egress, Instant createdAt, String participantSid) {

    public MediaWebhookEvent(String id, Type type, String rawType, String roomName, String participantIdentity,
                             EgressUpdate egress, Instant createdAt) {
        this(id, type, rawType, roomName, participantIdentity, egress, createdAt, null);
    }

    public enum Type {
        ROOM_STARTED, ROOM_FINISHED, PARTICIPANT_JOINED, PARTICIPANT_LEFT,
        EGRESS_STARTED, EGRESS_UPDATED, EGRESS_ENDED, OTHER
    }

    public enum EgressStatus { STARTING, ACTIVE, ENDING, COMPLETE, FAILED }

    public record EgressUpdate(String egressId, EgressStatus status, String error, String filename, Long sizeBytes,
                               Long durationSeconds, Instant startedAt, Instant endedAt) {
    }
}
