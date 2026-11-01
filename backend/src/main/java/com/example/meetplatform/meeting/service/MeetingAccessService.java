package com.example.meetplatform.meeting.service;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.repository.MeetingInvitationRepository;
import com.example.meetplatform.organization.service.OrganizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Resource-level authorization for meetings. Never trusts roles supplied by clients. */
@Service
public class MeetingAccessService {

    private final MeetingInvitationRepository invitations;
    private final MeetingParticipationLookup participation;
    private final OrganizationService organizations;

    public MeetingAccessService(MeetingInvitationRepository invitations, MeetingParticipationLookup participation,
                                OrganizationService organizations) {
        this.invitations = invitations;
        this.participation = participation;
        this.organizations = organizations;
    }

    public boolean isHost(Meeting meeting, UUID userId) {
        return userId != null && meeting.getHostId().equals(userId);
    }

    @Transactional(readOnly = true)
    public boolean isInvited(Meeting meeting, UUID userId, String email) {
        return userId != null && invitations.isInvited(meeting.getId(), userId, email == null ? "" : email);
    }

    @Transactional(readOnly = true)
    public boolean isOrgMember(Meeting meeting, UUID userId) {
        return meeting.getOrganizationId() != null && organizations.isMember(meeting.getOrganizationId(), userId);
    }

    /** Who may see the full meeting details (not just the public join summary). */
    @Transactional(readOnly = true)
    public boolean canView(Meeting meeting, CurrentUser user) {
        if (user == null) {
            return false;
        }
        return isHost(meeting, user.userId())
                || user.systemAdmin()
                || isInvited(meeting, user.userId(), user.email())
                || participation.hasParticipated(meeting.getId(), user.userId())
                || isOrgMember(meeting, user.userId());
    }

    /** Responds 404 rather than 403 so meeting ids cannot be probed. */
    public void requireView(Meeting meeting, CurrentUser user) {
        if (!canView(meeting, user)) {
            throw ApiException.notFound("Meeting");
        }
    }

    public void requireHost(Meeting meeting, CurrentUser user) {
        if (!isHost(meeting, user.userId())) {
            if (canView(meeting, user)) {
                throw ApiException.forbidden("Only the meeting host can do this.");
            }
            throw ApiException.notFound("Meeting");
        }
    }

    /**
     * Role the user would hold when joining now. CO_HOST is scoped to the live session (requirements: "Meeting
     * (live session)"): it is only kept on a re-join of the same session, never inherited by a later session.
     */
    @Transactional(readOnly = true)
    public MeetingRole roleFor(Meeting meeting, UUID userId) {
        if (userId == null) {
            return MeetingRole.GUEST;
        }
        if (isHost(meeting, userId)) {
            return MeetingRole.HOST;
        }
        return participation.liveSessionRole(meeting.getId(), userId)
                .filter(r -> r == MeetingRole.CO_HOST)
                .orElse(MeetingRole.PARTICIPANT);
    }
}
