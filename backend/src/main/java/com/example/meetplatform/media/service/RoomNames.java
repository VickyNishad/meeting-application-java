package com.example.meetplatform.media.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Media room names of a session. Every session has a stage room (its stored room name). In a HOST_AND_SELF_ONLY
 * session every attendee also has an audience room of its own: the attendee publishes there and may not subscribe,
 * and only the stage joins it (hidden, subscribing). One room per attendee, because the SFU tells everyone visible in
 * a room who else is there (identities, joins, mute state, speaking): with a shared audience room attendees would
 * learn about each other even without receiving each other's media.
 */
public final class RoomNames {

    private static final String AUDIENCE_MARK = "-aud-";

    private RoomNames() {
    }

    /** The audience room of one attendee. */
    public static String audience(String stageRoomName, UUID attendeeId) {
        return stageRoomName + AUDIENCE_MARK + attendeeId;
    }

    public static boolean isAudience(String roomName) {
        return roomName != null && roomName.contains(AUDIENCE_MARK);
    }

    /** The stage room a room belongs to (itself for a stage room). */
    public static String stageOf(String roomName) {
        return isAudience(roomName) ? roomName.substring(0, roomName.lastIndexOf(AUDIENCE_MARK)) : roomName;
    }

    /** The attendee whose audience room this is, if it is one. */
    public static Optional<UUID> audienceOwner(String roomName) {
        if (!isAudience(roomName)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(roomName.substring(roomName.lastIndexOf(AUDIENCE_MARK) + AUDIENCE_MARK.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
