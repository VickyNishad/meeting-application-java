package com.example.meetplatform.participant.service;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.util.Tokens;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.meeting.service.MeetingAccessService;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates the caller as a specific participant: either the signed-in owner of the participant row,
 * or anyone presenting that participant's secret (guests).
 */
@Service
public class ParticipantAccessService {

    private final MeetingParticipantRepository participants;
    private final MeetingRepository meetings;
    private final MeetingAccessService meetingAccess;

    public ParticipantAccessService(MeetingParticipantRepository participants, MeetingRepository meetings,
                                    MeetingAccessService meetingAccess) {
        this.participants = participants;
        this.meetings = meetings;
        this.meetingAccess = meetingAccess;
    }

    /**
     * Read access to meeting content (chat history, shared files): a verified participant of the meeting that was
     * admitted (and not removed), or a signed-in user allowed to view the meeting. A participant secret that only
     * ever waited, or was denied or removed, keeps working for join-status and leave but grants no content access.
     */
    @Transactional(readOnly = true)
    public void requireMeetingViewer(UUID meetingId, UUID participantId, String participantToken,
                                     Optional<CurrentUser> user) {
        if (participantId != null) {
            if (resolve(meetingId, participantId, participantToken, user).wasAdmitted()) {
                return;
            }
        } else if (participantToken != null && !participantToken.isBlank()
                && participants.findByMeetingIdAndWaitTokenHash(meetingId, Tokens.sha256(participantToken))
                .filter(MeetingParticipant::wasAdmitted).isPresent()) {
            return;
        }
        CurrentUser signedIn = user.orElseThrow(() -> ApiException.forbidden("Not a participant of this meeting."));
        meetingAccess.requireView(meetings.findById(meetingId).orElseThrow(() -> ApiException.notFound("Meeting")),
                signedIn);
    }

    @Transactional(readOnly = true)
    public MeetingParticipant resolve(UUID meetingId, UUID participantId, String participantToken,
                                      Optional<CurrentUser> user) {
        MeetingParticipant participant = participants.findByIdAndMeetingId(participantId, meetingId)
                .orElseThrow(() -> ApiException.forbidden("Not a participant of this meeting."));
        boolean owner = user.isPresent() && participant.getUserId() != null
                && participant.getUserId().equals(user.get().userId());
        if (!owner && !Tokens.matchesHash(participantToken, participant.getWaitTokenHash())) {
            throw ApiException.forbidden("Not a participant of this meeting.");
        }
        return participant;
    }

    /** Same as {@link #resolve} but additionally requires the participant to be in the live session. */
    @Transactional(readOnly = true)
    public MeetingParticipant resolvePresent(UUID meetingId, UUID participantId, String participantToken,
                                             Optional<CurrentUser> user) {
        MeetingParticipant participant = resolve(meetingId, participantId, participantToken, user);
        if (!participant.isPresent()) {
            throw ApiException.forbidden("You are not currently in this meeting.");
        }
        return participant;
    }
}
