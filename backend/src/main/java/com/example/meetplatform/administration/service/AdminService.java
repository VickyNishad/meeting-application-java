package com.example.meetplatform.administration.service;

import com.example.meetplatform.booking.repository.BookingRepository;
import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditLog;
import com.example.meetplatform.common.audit.AuditLogRepository;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.response.PageParams;
import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.identity.dto.UserDtos.UserProfile;
import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
import com.example.meetplatform.identity.entity.SystemRole;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.entity.UserStatus;
import com.example.meetplatform.identity.mapper.UserMapper;
import com.example.meetplatform.identity.repository.UserRepository;
import com.example.meetplatform.identity.service.SessionService;
import com.example.meetplatform.identity.service.UserService;
import com.example.meetplatform.meeting.dto.MeetingDtos.MeetingView;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingStatus;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.meeting.service.MeetingService;
import com.example.meetplatform.organization.repository.OrganizationRepository;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.recording.repository.RecordingRepository;
import com.example.meetplatform.room.service.RoomSessionService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AdminService {

    private final UserRepository users;
    private final UserService userService;
    private final SessionService sessions;
    private final OrganizationRepository organizations;
    private final MeetingRepository meetings;
    private final MeetingService meetingService;
    private final MeetingParticipantRepository participants;
    private final RoomSessionService roomSessions;
    private final RecordingRepository recordings;
    private final BookingRepository bookings;
    private final AuditLogRepository auditLogs;
    private final AuditService audit;
    private final Clock clock;

    public AdminService(UserRepository users, UserService userService, SessionService sessions,
                        OrganizationRepository organizations, MeetingRepository meetings, MeetingService meetingService,
                        MeetingParticipantRepository participants, RoomSessionService roomSessions,
                        RecordingRepository recordings, BookingRepository bookings, AuditLogRepository auditLogs,
                        AuditService audit, Clock clock) {
        this.users = users;
        this.userService = userService;
        this.sessions = sessions;
        this.organizations = organizations;
        this.meetings = meetings;
        this.meetingService = meetingService;
        this.participants = participants;
        this.roomSessions = roomSessions;
        this.recordings = recordings;
        this.bookings = bookings;
        this.auditLogs = auditLogs;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        Instant startOfDay = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return new Stats(users.count(), organizations.count(), meetings.countByCreatedAtGreaterThanEqual(startOfDay),
                meetings.countByStatus(MeetingStatus.LIVE), participants.countByState(MeetingParticipant.State.JOINED),
                recordings.totalStoredBytes(), bookings.countByCreatedAtGreaterThanEqual(startOfDay));
    }

    @Transactional(readOnly = true)
    public PageResponse<UserProfile> users(String q, Integer page, Integer size) {
        var pageable = PageParams.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> result = q == null || q.isBlank() ? users.findAll(pageable)
                : users.search("%" + q.trim().toLowerCase(Locale.ROOT) + "%", pageable);
        return PageResponse.of(result, UserMapper::toProfile);
    }

    @Transactional
    public UserProfile updateUser(CurrentUser admin, UUID userId, UserStatus status, SystemRole role) {
        User user = userService.require(userId);
        if (admin.userId().equals(userId) && (status == UserStatus.DISABLED || role == SystemRole.USER)) {
            throw new ApiException(ErrorCode.CONFLICT, "You cannot disable or demote your own account.");
        }
        if (status != null) {
            user.setStatus(status);
            if (status == UserStatus.DISABLED) {
                sessions.revokeAll(userId, "DISABLED_BY_ADMIN");
            }
        }
        if (role != null) {
            user.setSystemRole(role);
            // Role is embedded in access tokens: force re-authentication so it takes effect immediately.
            sessions.revokeAll(userId, "ROLE_CHANGED");
        }
        audit.record(AuditActions.USER_UPDATED_BY_ADMIN, AuditActions.RESOURCE_USER, userId,
                Map.of("status", String.valueOf(status), "systemRole", String.valueOf(role)));
        return UserMapper.toProfile(user);
    }

    @Transactional(readOnly = true)
    public List<LiveMeeting> liveMeetings() {
        List<LiveMeeting> result = new ArrayList<>();
        for (Meeting m : meetings.findByStatus(MeetingStatus.LIVE)) {
            long count = roomSessions.active(m.getId())
                    .map(r -> participants.countByRoomIdAndState(r.getId(), MeetingParticipant.State.JOINED)).orElse(0L);
            result.add(new LiveMeeting(meetingService.view(m, MeetingRole.PARTICIPANT), count));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditView> auditLogs(UUID actorId, String action, Instant from, Instant to, Integer page,
                                             Integer size) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (actorId != null) {
                p.add(cb.equal(root.get("actorUserId"), actorId));
            }
            if (action != null && !action.isBlank()) {
                p.add(cb.equal(root.get("action"), action.trim().toUpperCase(Locale.ROOT)));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                p.add(cb.lessThan(root.get("createdAt"), to));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<AuditLog> result = auditLogs.findAll(spec, PageParams.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, UserSummary> actors = userService.summaries(result.getContent().stream()
                .map(AuditLog::getActorUserId).filter(Objects::nonNull).distinct().toList());
        return PageResponse.of(result, l -> new AuditView(l.getId(),
                l.getActorUserId() == null ? null : actors.get(l.getActorUserId()), l.getAction(), l.getResourceType(),
                l.getResourceId(), l.getDetails(), l.getIpAddress(), l.getCreatedAt()));
    }

    public record Stats(long users, long organizations, long meetingsToday, long liveMeetings, long liveParticipants,
                        long recordingsStorageBytes, long bookingsToday) {
    }

    public record LiveMeeting(MeetingView meeting, long participantCount) {
    }

    public record AuditView(UUID id, UserSummary actor, String action, String resourceType, UUID resourceId,
                            String details, String ipAddress, Instant createdAt) {
    }
}
