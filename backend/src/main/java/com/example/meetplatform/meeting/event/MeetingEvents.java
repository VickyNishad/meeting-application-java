package com.example.meetplatform.meeting.event;

import java.util.List;
import java.util.UUID;

public final class MeetingEvents {

    private MeetingEvents() {
    }

    public record InvitationsCreated(UUID meetingId, List<UUID> invitationIds) {
    }

    public record MeetingRescheduled(UUID meetingId) {
    }

    public record MeetingCancelled(UUID meetingId, String reason) {
    }

    /** Published inside the update transaction when the host switches automatic recording on or off. */
    public record AutoRecordChanged(UUID meetingId, boolean enabled) {
    }
}
