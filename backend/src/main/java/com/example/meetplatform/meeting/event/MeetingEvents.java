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

    /**
     * Published inside the update transaction when the media mode or the screen-sharing policy changes: a live
     * session's media grants follow after commit.
     */
    public record MediaSettingsChanged(UUID meetingId) {
    }

    /**
     * Someone in the live session really spoke: a speaking clip was opened (the browser opens one only once it hears a
     * voice) or speaking time was reported. Published inside the transaction that recorded it; automatic recording of
     * the meeting starts on the first one, so a session in which nobody speaks leaves no recording.
     */
    public record SpeechDetected(UUID meetingId, UUID participantId) {
    }
}
