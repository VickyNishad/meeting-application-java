package com.example.meetplatform.participant.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.security.RateLimiter;
import com.example.meetplatform.common.util.Tokens;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.service.UserService;
import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaKind;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingStatus;
import com.example.meetplatform.meeting.entity.MeetingEnums.Privacy;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.meeting.service.MeetingAccessService;
import com.example.meetplatform.meeting.service.MeetingService;
import com.example.meetplatform.participant.dto.ParticipantDtos;
import com.example.meetplatform.participant.dto.ParticipantDtos.JoinRequest;
import com.example.meetplatform.participant.dto.ParticipantDtos.JoinResponse;
import com.example.meetplatform.participant.dto.ParticipantDtos.JoinStatus;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.entity.MeetingParticipant.State;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.service.RoomSessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Admission control: authorizes a join, enforces privacy, password, lock, capacity and waiting room, and
 * issues the short-lived media token. All checks run under the meeting row lock so that concurrent joins
 * observe a consistent seat count and never start two rooms.
 */
@Service
public class JoinService {

    static final Duration ADMISSION_GRACE = Duration.ofMinutes(2);

    private final MeetingRepository meetings;
    private final MeetingService meetingService;
    private final MeetingAccessService access;
    private final MeetingParticipantRepository participants;
    private final ParticipantAccessService participantAccess;
    private final RoomSessionService roomSessions;
    private final ParticipantPolicy policy;
    private final MediaService media;
    private final RoomSignals signals;
    private final UserService users;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final Duration reconnectWindow;
    private final Clock clock;

    public JoinService(MeetingRepository meetings, MeetingService meetingService, MeetingAccessService access,
                       MeetingParticipantRepository participants, ParticipantAccessService participantAccess,
                       RoomSessionService roomSessions, ParticipantPolicy policy, MediaService media,
                       RoomSignals signals, UserService users, PasswordEncoder passwordEncoder,
                       RateLimiter rateLimiter, AuditService audit,
                       @Value("${app.meetings.reconnect-window:PT30S}") Duration reconnectWindow, Clock clock) {
        this.reconnectWindow = reconnectWindow;
        this.meetings = meetings;
        this.meetingService = meetingService;
        this.access = access;
        this.participants = participants;
        this.participantAccess = participantAccess;
        this.roomSessions = roomSessions;
        this.policy = policy;
        this.media = media;
        this.signals = signals;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public JoinResponse join(UUID meetingId, JoinRequest request, Optional<CurrentUser> currentUser, ClientInfo client) {
        rateLimiter.check("join-ip", client.ipAddress(), 60, Duration.ofMinutes(1));
        Meeting meeting = meetings.findByIdForUpdate(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        ensureJoinable(meeting);

        CurrentUser user = currentUser.orElse(null);
        UUID userId = user == null ? null : user.userId();
        MeetingRole role = access.roleFor(meeting, userId);
        boolean host = role == MeetingRole.HOST;
        String displayName = resolveDisplayName(user, request);

        if (!host) {
            authorizeEntry(meeting, user);
            checkPassword(meeting, request.password(), client);
        }

        Optional<MeetingRoom> activeRoom = roomSessions.active(meetingId);
        Optional<MeetingParticipant> previous = userId == null ? provenGuestParticipation(meetingId, request.rejoin())
                : participants.findFirstByMeetingIdAndUserIdOrderByCreatedAtDesc(meetingId, userId);
        if (previous.isPresent() && activeRoom.isPresent() && previous.get().getState() == State.REMOVED
                && activeRoom.get().getId().equals(previous.get().getRoomId())) {
            throw ApiException.forbidden("You were removed from this meeting by the host.");
        }

        MeetingRoom room = activeRoom.orElse(null);
        boolean startsSession = room == null && (role.isModerator() || meeting.isJoinBeforeHost());
        MeetingParticipant participant = reusable(previous, room).orElse(null);
        // Back within the reconnect window (reload, second device, role-change reconnect whose leave was processed
        // first): the seat and the role it had in this session are taken back, as by a reconnect.
        boolean rejoining = participant != null && participant.getState() == State.LEFT;
        boolean alreadySeated = participant != null && (participant.isPresent() || rejoining);
        if ((room != null || startsSession) && !alreadySeated) {
            if (meeting.isLocked() && !role.isModerator()) {
                audit.record(userId, null, AuditActions.MEETING_JOIN_DENIED, AuditActions.RESOURCE_MEETING, meetingId,
                        Map.of("reason", "LOCKED"));
                throw new ApiException(ErrorCode.MEETING_LOCKED, "The host has locked this meeting.");
            }
            // Hosts and co-hosts are never turned away for capacity (they still occupy a seat).
            if (room != null && !role.isModerator() && occupiedSeats(room) >= meeting.getMaxParticipants()) {
                throw new ApiException(ErrorCode.MEETING_FULL, "This meeting has reached its participant limit.");
            }
        }
        // Every check that can reject this join has run: only now create the media room, so a rejected join
        // never leaves an orphan room on the SFU.
        if (startsSession) {
            room = roomSessions.start(meeting, userId);
            assignWaitingToRoom(meeting, room, participant, role.isModerator() ? 0 : 1);
        }

        String waitToken = Tokens.randomSecret(24);
        if (participant == null) {
            participant = new MeetingParticipant(meetingId, room == null ? null : room.getId(), userId, displayName,
                    role, Tokens.sha256(waitToken), client.ipAddress());
            // Back in the same session after the reconnect window: the host's media overrides for this account (or
            // guest participation proven by its secret) still apply.
            MeetingParticipant before = previous.orElse(null);
            if (before != null && room != null && room.getId().equals(before.getRoomId())) {
                for (MediaKind kind : MediaKind.values()) {
                    participant.setMediaOverride(kind, before.getMediaOverride(kind));
                }
            }
        } else {
            participant.setWaitTokenHash(Tokens.sha256(waitToken));
        }
        participant.setMasked(ParticipantPolicy.isMasked(meeting, participant.getRole()));

        String waitingReason = null;
        if (room == null) {
            waitingReason = "WAITING_FOR_HOST";
        } else if (!alreadySeated) {
            if (meeting.isWaitingRoomEnabled() && !role.isModerator()) {
                waitingReason = "WAITING_ROOM";
                participant.setRoomId(room.getId());
            } else {
                participant.admit(room.getId(), clock.instant());
            }
        }
        if (rejoining) {
            participant.admit(room.getId(), clock.instant());
        }
        participants.save(participant);

        if (participant.getState() == State.WAITING && room != null) {
            notifyModeratorsOfWaiting(meeting, room);
        }
        // The participation's role, not the meeting role: it may have been promoted or demoted in this session, and the
        // media tokens are issued for it.
        return response(meeting, participant, waitToken, room, waitingReason, participant.getRole());
    }

    @Transactional(readOnly = true)
    public JoinResponse status(UUID meetingId, UUID participantId, String waitToken, Optional<CurrentUser> user) {
        MeetingParticipant participant = participantAccess.resolve(meetingId, participantId, waitToken, user);
        Meeting meeting = meetingService.require(meetingId);
        if (meeting.getStatus() == MeetingStatus.CANCELLED) {
            throw new ApiException(ErrorCode.MEETING_CANCELLED, "This meeting was cancelled.");
        }
        // Echo the participant secret back (only when the caller proved it) so clients always have it after admission.
        String secret = Tokens.matchesHash(waitToken, participant.getWaitTokenHash()) ? waitToken : null;
        return switch (participant.getState()) {
            case WAITING -> response(meeting, participant, secret, null,
                    participant.getRoomId() == null ? "WAITING_FOR_HOST" : "WAITING_ROOM", participant.getRole());
            case ADMITTED, JOINED -> {
                MeetingRoom room = roomSessions.active(meetingId)
                        .filter(r -> r.getId().equals(participant.getRoomId()))
                        .orElseThrow(() -> new ApiException(ErrorCode.MEETING_NOT_ACTIVE,
                                "This session has ended. Join the meeting again."));
                yield response(meeting, participant, secret, room, null, participant.getRole());
            }
            default -> new JoinResponse(JoinStatus.DENIED, participant.getId(), null, participant.getRole(),
                    null, null, null, null, meetingService.joinView(meeting, participant.getRole()));
        };
    }

    // ------------------------------------------------------------------ helpers

    private void ensureJoinable(Meeting meeting) {
        if (meeting.getStatus() == MeetingStatus.CANCELLED) {
            throw new ApiException(ErrorCode.MEETING_CANCELLED, "This meeting was cancelled.");
        }
        if (meeting.isClosed()) {
            throw new ApiException(ErrorCode.MEETING_ENDED, "This meeting has ended.");
        }
    }

    private String resolveDisplayName(CurrentUser user, JoinRequest request) {
        if (user != null) {
            User account = users.require(user.userId());
            if (!account.isActive()) {
                throw new ApiException(ErrorCode.ACCOUNT_DISABLED, "This account is disabled.");
            }
            return account.getDisplayName();
        }
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "displayName is required to join as a guest.");
        }
        return request.displayName().trim();
    }

    private void authorizeEntry(Meeting meeting, CurrentUser user) {
        if (user == null) {
            if (!meeting.isAllowGuests() || meeting.getPrivacy() != Privacy.LINK) {
                throw new ApiException(ErrorCode.GUESTS_NOT_ALLOWED, "Sign in to join this meeting.");
            }
            return;
        }
        boolean invited = access.isInvited(meeting, user.userId(), user.email());
        switch (meeting.getPrivacy()) {
            case INVITEES_ONLY -> {
                if (!invited) {
                    throw new ApiException(ErrorCode.NOT_INVITED, "Only invited people can join this meeting.");
                }
            }
            case ORGANIZATION -> {
                if (!invited && !access.isOrgMember(meeting, user.userId())) {
                    throw new ApiException(ErrorCode.NOT_INVITED, "This meeting is restricted to its organization.");
                }
            }
            case LINK -> {
            }
        }
    }

    private void checkPassword(Meeting meeting, String password, ClientInfo client) {
        if (meeting.getPasswordHash() == null) {
            return;
        }
        if (password == null || password.isEmpty()) {
            throw new ApiException(ErrorCode.MEETING_PASSWORD_REQUIRED, "This meeting requires a password.");
        }
        rateLimiter.check("meeting-password", client.ipAddress() + ":" + meeting.getId(), 10, Duration.ofMinutes(10));
        if (!passwordEncoder.matches(password, meeting.getPasswordHash())) {
            throw new ApiException(ErrorCode.MEETING_PASSWORD_INVALID, "The meeting password is incorrect.");
        }
    }

    /**
     * Re-use the caller's open participation in this session (page reload, second tab) instead of a new seat; also one
     * that left this live session within the reconnect window.
     */
    private Optional<MeetingParticipant> reusable(Optional<MeetingParticipant> previous, MeetingRoom room) {
        Instant now = clock.instant();
        return previous.filter(p -> p.isOpen()
                        || (room != null && room.getId().equals(p.getRoomId()) && p.leftWithin(now, reconnectWindow)))
                .filter(p -> room == null ? p.getRoomId() == null
                        : p.getRoomId() == null || room.getId().equals(p.getRoomId()));
    }

    /**
     * A guest's earlier participation in this meeting, when the guest proves it with the participant secret it was given
     * (a guest has no account to recognise it by). Anything that does not match is ignored: the guest joins as new.
     */
    private Optional<MeetingParticipant> provenGuestParticipation(UUID meetingId, ParticipantDtos.Rejoin rejoin) {
        if (rejoin == null) {
            return Optional.empty();
        }
        return participants.findByIdAndMeetingId(rejoin.participantId(), meetingId)
                .filter(p -> p.getUserId() == null && Tokens.matchesHash(rejoin.token(), p.getWaitTokenHash()));
    }

    private long occupiedSeats(MeetingRoom room) {
        return participants.countOccupiedSeats(room.getId(), clock.instant().minus(ADMISSION_GRACE));
    }

    /**
     * Session just started: people waiting for the host move into the room, earliest first. They are admitted
     * unless a waiting room applies or the room is full (moderators are exempt from capacity); the rest stay
     * WAITING in this session's waiting room for a moderator to admit. The joiner who starts the session is
     * handled by the join itself, and {@code reservedSeats} keeps a seat free for a non-moderator starter.
     */
    private void assignWaitingToRoom(Meeting meeting, MeetingRoom room, MeetingParticipant joiner, int reservedSeats) {
        Instant now = clock.instant();
        long seated = occupiedSeats(room) + reservedSeats;
        for (MeetingParticipant waiting : participants.findWithoutRoom(meeting.getId(), State.WAITING)) {
            if (joiner != null && waiting.getId().equals(joiner.getId())) {
                continue;
            }
            boolean moderator = waiting.getRole().isModerator();
            if (!moderator && (meeting.isWaitingRoomEnabled() || seated >= meeting.getMaxParticipants())) {
                waiting.setRoomId(room.getId());
            } else {
                waiting.admit(room.getId(), now);
                seated++;
            }
        }
    }

    private void notifyModeratorsOfWaiting(Meeting meeting, MeetingRoom room) {
        List<String> moderators = participants.findByRoomIdAndStateIn(room.getId(), MeetingParticipant.PRESENT).stream()
                .filter(p -> p.getRole().isModerator())
                .map(p -> p.getId().toString())
                .toList();
        long waiting = participants.findByMeetingIdAndStateOrderByCreatedAtAsc(meeting.getId(), State.WAITING).size();
        signals.sendTo(room.getLivekitRoomName(), RoomSignals.TOPIC_MODERATION,
                Map.of("type", "waiting-room", "count", waiting), moderators);
    }

    private JoinResponse response(Meeting meeting, MeetingParticipant participant, String waitToken, MeetingRoom room,
                                  String waitingReason, MeetingRole role) {
        var view = meetingService.joinView(meeting, role);
        if (participant.isPresent() && room != null) {
            ParticipantPolicy.MediaAccess access = policy.issueAccess(meeting, participant, room.getLivekitRoomName());
            return new JoinResponse(JoinStatus.ADMITTED, participant.getId(), waitToken, role, media.clientUrl(),
                    access.token(), room.getLivekitRoomName(), null, view, access.audienceRoomName(),
                    access.audienceToken());
        }
        return new JoinResponse(JoinStatus.WAITING, participant.getId(), waitToken, role, null, null, null,
                waitingReason, view);
    }
}
