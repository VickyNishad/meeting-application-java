package com.example.meetplatform.room.event;

import java.util.UUID;

public final class RoomEvents {

    private RoomEvents() {
    }

    public record SessionStarted(UUID meetingId, UUID roomId, String roomName) {
    }

    public record SessionEnded(UUID meetingId, UUID roomId, String roomName, String reason) {
    }
}
