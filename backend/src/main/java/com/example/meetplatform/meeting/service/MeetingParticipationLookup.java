package com.example.meetplatform.meeting.service;

import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;

import java.util.Optional;
import java.util.UUID;

/**
 * Participation facts the meeting module needs for access decisions. Implemented by the participant module,
 * which keeps the dependency direction participant -> meeting.
 */
public interface MeetingParticipationLookup {

    /** Admitted to a session of the meeting (not merely waiting, denied or removed). */
    boolean hasParticipated(UUID meetingId, UUID userId);

    /**
     * Role of the user's participation in the meeting's current live session, if any (e.g. CO_HOST carried over
     * to a re-join of the same session). Roles never carry over from earlier sessions, and a removed or denied
     * participation yields nothing.
     */
    Optional<MeetingRole> liveSessionRole(UUID meetingId, UUID userId);

    /** Role of the user while they are actually in the meeting's live session right now (not waiting, not left). */
    Optional<MeetingRole> presentRole(UUID meetingId, UUID userId);
}
