package com.example.meetplatform.meeting.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.response.PageParams;
import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.security.RateLimiter;
import com.example.meetplatform.common.util.Tokens;
import com.example.meetplatform.common.validation.TimeZones;
import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.service.UserService;
import com.example.meetplatform.meeting.dto.MeetingDtos.CreateMeetingRequest;
import com.example.meetplatform.meeting.dto.MeetingDtos.InvitationView;
import com.example.meetplatform.meeting.dto.MeetingDtos.MeetingSummaryByCode;
import com.example.meetplatform.meeting.dto.MeetingDtos.MeetingView;
import com.example.meetplatform.meeting.dto.MeetingDtos.UpdateMeetingRequest;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingStatus;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingType;
import com.example.meetplatform.meeting.entity.MeetingEnums.Privacy;
import com.example.meetplatform.meeting.entity.MeetingInvitation;
import com.example.meetplatform.meeting.event.MeetingEvents;
import com.example.meetplatform.meeting.mapper.MeetingMapper;
import com.example.meetplatform.meeting.repository.MeetingInvitationRepository;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.organization.service.OrganizationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MeetingService {

    private static final int JOIN_CODE_LENGTH = 10;
    private static final int DEFAULT_DURATION_MINUTES = 60;
    private static final Duration START_GRACE = Duration.ofMinutes(5);
    /** Per-host budget of invitation e-mails (each new invitee is one e-mail). */
    private static final int INVITATIONS_PER_HOUR = 500;
    /** Per-host budget of reschedules that notify invitees (each e-mails every invitee). */
    private static final int RESCHEDULES_PER_HOUR = 20;

    private final MeetingRepository meetings;
    private final MeetingInvitationRepository invitations;
    private final MeetingAccessService access;
    private final MeetingMapper mapper;
    private final UserService users;
    private final OrganizationService organizations;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final RateLimiter rateLimiter;
    private final AppProperties.Meetings props;
    private final Clock clock;

    public MeetingService(MeetingRepository meetings, MeetingInvitationRepository invitations,
                          MeetingAccessService access, MeetingMapper mapper, UserService users,
                          OrganizationService organizations, PasswordEncoder passwordEncoder, AuditService audit,
                          ApplicationEventPublisher events, RateLimiter rateLimiter, AppProperties properties,
                          Clock clock) {
        this.meetings = meetings;
        this.invitations = invitations;
        this.access = access;
        this.mapper = mapper;
        this.users = users;
        this.organizations = organizations;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.events = events;
        this.rateLimiter = rateLimiter;
        this.props = properties.meetings();
        this.clock = clock;
    }

    @Transactional
    public MeetingView create(CurrentUser user, CreateMeetingRequest request) {
        rateLimiter.check("meeting-create", user.userId().toString(), 60, Duration.ofHours(1));
        if (request.type() == MeetingType.PERSONAL_ROOM) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Use /rooms/personal for the personal meeting room.");
        }
        TimeZones.require(request.timeZone());
        Instant now = clock.instant();
        Meeting meeting = new Meeting(newJoinCode(), request.title().trim(), request.type(), user.userId(), request.timeZone());
        meeting.setDescription(blankToNull(request.description()));
        if (request.mediaMode() != null) {
            meeting.setMediaMode(request.mediaMode());
        }
        applyOrganization(meeting, user, request.organizationId(), request.privacy());

        int duration = request.durationMinutes() == null ? DEFAULT_DURATION_MINUTES : request.durationMinutes();
        Instant start = switch (request.type()) {
            case SCHEDULED -> {
                if (request.startTime() == null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "startTime is required for scheduled meetings.");
                }
                if (request.startTime().isBefore(now.minus(START_GRACE))) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "startTime must be in the future.");
                }
                yield request.startTime();
            }
            default -> request.startTime() == null ? now : request.startTime();
        };
        meeting.setStartTime(start);
        meeting.setEndTime(start.plus(Duration.ofMinutes(duration)));
        if (request.recurrenceRule() != null && !request.recurrenceRule().isBlank()) {
            if (request.type() != MeetingType.SCHEDULED) {
                throw new ApiException(ErrorCode.INVALID_RECURRENCE, "Only scheduled meetings can recur.");
            }
            meeting.setRecurrenceRule(RecurrenceRules.normalize(request.recurrenceRule()));
        }
        if (request.password() != null && !request.password().isBlank()) {
            meeting.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        meeting.setWaitingRoomEnabled(Boolean.TRUE.equals(request.waitingRoomEnabled()));
        if (request.allowGuests() != null) {
            meeting.setAllowGuests(request.allowGuests() && meeting.isAllowGuests());
        }
        if (request.joinBeforeHost() != null) {
            meeting.setJoinBeforeHost(request.joinBeforeHost());
        }
        meeting.setMuteOnEntry(Boolean.TRUE.equals(request.muteOnEntry()));
        meeting.setAutoRecord(Boolean.TRUE.equals(request.autoRecord()));
        if (request.visibilityMode() != null) {
            meeting.setVisibilityMode(request.visibilityMode());
        }
        meeting.setMaxParticipants(request.type() == MeetingType.ONE_TO_ONE ? 2
                : capacity(request.maxParticipants()));
        if (request.screenSharePolicy() != null) {
            meeting.setScreenSharePolicy(request.screenSharePolicy());
        }
        meetings.save(meeting);

        List<UUID> invited = invite(meeting, request.inviteeEmails(), user.email(), user.userId());
        audit.record(AuditActions.MEETING_CREATED, AuditActions.RESOURCE_MEETING, meeting.getId(),
                Map.of("type", meeting.getType(), "invitees", invited.size()));
        if (!invited.isEmpty()) {
            events.publishEvent(new MeetingEvents.InvitationsCreated(meeting.getId(), invited));
        }
        return view(meeting, MeetingRole.HOST);
    }

    @Transactional
    public MeetingView update(CurrentUser user, UUID meetingId, UpdateMeetingRequest request) {
        Meeting meeting = require(meetingId);
        access.requireHost(meeting, user);
        if (meeting.isClosed()) {
            throw new ApiException(ErrorCode.MEETING_ENDED, "Ended or cancelled meetings cannot be edited.");
        }
        if (request.version() != null && !request.version().equals(meeting.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Meeting.class, meetingId);
        }
        boolean rescheduled = false;
        if (request.title() != null) {
            meeting.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            meeting.setDescription(blankToNull(request.description()));
        }
        if (request.mediaMode() != null) {
            meeting.setMediaMode(request.mediaMode());
        }
        if (request.privacy() != null) {
            if (request.privacy() == Privacy.ORGANIZATION && meeting.getOrganizationId() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "ORGANIZATION privacy requires an organization.");
            }
            meeting.setPrivacy(request.privacy());
        }
        if (request.timeZone() != null) {
            meeting.setTimeZone(request.timeZone());
        }
        if (request.startTime() != null || request.durationMinutes() != null) {
            if (meeting.getType() == MeetingType.PERSONAL_ROOM) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Personal rooms have no schedule.");
            }
            Instant start = request.startTime() != null ? request.startTime() : meeting.getStartTime();
            if (request.startTime() != null && start.isBefore(clock.instant().minus(START_GRACE))) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "startTime must be in the future.");
            }
            long minutes = request.durationMinutes() != null ? request.durationMinutes()
                    : Duration.between(meeting.getStartTime(), meeting.getEndTime()).toMinutes();
            rescheduled = !start.equals(meeting.getStartTime())
                    || !start.plus(Duration.ofMinutes(minutes)).equals(meeting.getEndTime());
            meeting.setStartTime(start);
            meeting.setEndTime(start.plus(Duration.ofMinutes(minutes)));
        }
        if (request.recurrenceRule() != null) {
            meeting.setRecurrenceRule(RecurrenceRules.normalize(request.recurrenceRule()));
            rescheduled = true;
        }
        if (request.password() != null) {
            meeting.setPasswordHash(request.password().isBlank() ? null : passwordEncoder.encode(request.password()));
        }
        if (request.waitingRoomEnabled() != null) {
            meeting.setWaitingRoomEnabled(request.waitingRoomEnabled());
        }
        if (request.allowGuests() != null) {
            meeting.setAllowGuests(request.allowGuests() && organizationAllowsGuests(meeting));
        }
        if (request.joinBeforeHost() != null) {
            meeting.setJoinBeforeHost(request.joinBeforeHost());
        }
        if (request.muteOnEntry() != null) {
            meeting.setMuteOnEntry(request.muteOnEntry());
        }
        if (request.autoRecord() != null && request.autoRecord() != meeting.isAutoRecord()) {
            meeting.setAutoRecord(request.autoRecord());
            // Switching it off stops what auto-record is capturing, so nobody arriving later is recorded unnoticed.
            events.publishEvent(new MeetingEvents.AutoRecordChanged(meetingId, request.autoRecord()));
        }
        if (request.visibilityMode() != null && request.visibilityMode() != meeting.getVisibilityMode()) {
            // Tokens, names and track permissions of everyone connected depend on it: only between sessions.
            if (meeting.getStatus() == MeetingStatus.LIVE) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Who can see whom cannot be changed while the meeting is live.");
            }
            meeting.setVisibilityMode(request.visibilityMode());
        }
        if (request.maxParticipants() != null && meeting.getType() != MeetingType.ONE_TO_ONE) {
            meeting.setMaxParticipants(capacity(request.maxParticipants()));
        }
        if (request.screenSharePolicy() != null) {
            meeting.setScreenSharePolicy(request.screenSharePolicy());
        }
        meetings.flush();
        audit.record(AuditActions.MEETING_UPDATED, AuditActions.RESOURCE_MEETING, meetingId,
                Map.of("rescheduled", rescheduled));
        if (rescheduled) {
            // A reschedule e-mails every invitee: limit how often a host can trigger that.
            if (!invitations.findEmails(meetingId).isEmpty()) {
                rateLimiter.check("meeting-reschedule", user.userId().toString(), RESCHEDULES_PER_HOUR,
                        Duration.ofHours(1));
            }
            events.publishEvent(new MeetingEvents.MeetingRescheduled(meetingId));
        }
        return view(meeting, MeetingRole.HOST);
    }

    @Transactional
    public void cancel(CurrentUser user, UUID meetingId, String reason) {
        Meeting meeting = require(meetingId);
        access.requireHost(meeting, user);
        if (meeting.getStatus() == MeetingStatus.LIVE) {
            throw new ApiException(ErrorCode.CONFLICT, "End the live meeting before cancelling it.");
        }
        if (meeting.getStatus() != MeetingStatus.SCHEDULED || meeting.getType() == MeetingType.PERSONAL_ROOM) {
            throw new ApiException(ErrorCode.CONFLICT, "Only scheduled meetings can be cancelled.");
        }
        meeting.cancel(clock.instant(), blankToNull(reason));
        audit.record(AuditActions.MEETING_CANCELLED, AuditActions.RESOURCE_MEETING, meetingId,
                Map.of("reason", reason == null ? "" : reason));
        events.publishEvent(new MeetingEvents.MeetingCancelled(meetingId, reason));
        invitations.findByMeetingIdOrderByCreatedAtAsc(meetingId)
                .forEach(i -> i.setStatus(MeetingInvitation.Status.CANCELLED));
    }

    @Transactional(readOnly = true)
    public MeetingView get(CurrentUser user, UUID meetingId) {
        Meeting meeting = require(meetingId);
        access.requireView(meeting, user);
        return view(meeting, access.roleFor(meeting, user.userId()));
    }

    @Transactional(readOnly = true)
    public PageResponse<MeetingView> list(CurrentUser user, String scope, Integer page, Integer size) {
        String normalized = scope == null ? "upcoming" : scope.toLowerCase(Locale.ROOT);
        if (!Set.of("upcoming", "past", "live", "all").contains(normalized)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "scope must be upcoming, past, live or all.");
        }
        Page<Meeting> result = meetings.findVisible(user.userId(), user.email() == null ? "" : user.email().toLowerCase(Locale.ROOT),
                normalized, clock.instant(), PageParams.of(page, size, Sort.unsorted()));
        Map<UUID, UserSummary> hosts = users.summaries(result.getContent().stream().map(Meeting::getHostId).toList());
        return PageResponse.of(result, m -> mapper.toView(m, hosts.get(m.getHostId()),
                m.getHostId().equals(user.userId()) ? MeetingRole.HOST : MeetingRole.PARTICIPANT));
    }

    @Transactional(readOnly = true)
    public MeetingSummaryByCode byCode(String joinCode, String clientIp) {
        rateLimiter.check("meeting-by-code", clientIp, 120, Duration.ofMinutes(10));
        Meeting meeting = meetings.findByJoinCode(joinCode.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.notFound("Meeting"));
        User host = users.require(meeting.getHostId());
        return new MeetingSummaryByCode(meeting.getId(), meeting.getTitle(), host.getDisplayName(), meeting.getStatus(),
                meeting.getStartTime(), meeting.getMediaMode(), meeting.getPasswordHash() != null,
                meeting.isAllowGuests(), meeting.isWaitingRoomEnabled(), meeting.isAutoRecord(),
                mapper.recordingInProgress(meeting));
    }

    @Transactional(readOnly = true)
    public List<InvitationView> invitations(CurrentUser user, UUID meetingId) {
        Meeting meeting = require(meetingId);
        access.requireHost(meeting, user);
        return invitations.findByMeetingIdOrderByCreatedAtAsc(meetingId).stream()
                .map(i -> new InvitationView(i.getId(), i.getEmail(), i.getStatus(), i.getSentAt()))
                .toList();
    }

    @Transactional
    public void addInvitations(CurrentUser user, UUID meetingId, List<String> emails) {
        Meeting meeting = require(meetingId);
        access.requireHost(meeting, user);
        if (meeting.isClosed()) {
            throw new ApiException(ErrorCode.MEETING_ENDED, "Cannot invite people to an ended or cancelled meeting.");
        }
        List<UUID> created = invite(meeting, emails, user.email(), user.userId());
        if (!created.isEmpty()) {
            events.publishEvent(new MeetingEvents.InvitationsCreated(meetingId, created));
        }
    }

    /** The caller's permanent personal meeting room, created on first use. */
    @Transactional
    public MeetingView personalRoom(CurrentUser user) {
        Meeting room = meetings.findPersonalRoom(user.userId()).orElseGet(() -> {
            User owner = users.require(user.userId());
            Meeting created = new Meeting(newJoinCode(), owner.getDisplayName() + "'s room", MeetingType.PERSONAL_ROOM,
                    owner.getId(), owner.getTimeZone());
            created.setWaitingRoomEnabled(true);
            created.setMaxParticipants(props.defaultMaxParticipants());
            return meetings.save(created);
        });
        return view(room, MeetingRole.HOST);
    }

    @Transactional
    public MeetingView updatePersonalRoom(CurrentUser user, UpdateMeetingRequest request) {
        MeetingView room = personalRoom(user);
        return update(user, room.id(), request);
    }

    // ------------------------------------------------------------------ API for other modules

    @Transactional(readOnly = true)
    public Meeting require(UUID meetingId) {
        return meetings.findById(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
    }

    @Transactional(readOnly = true)
    public MeetingView view(Meeting meeting, MeetingRole myRole) {
        return mapper.toView(meeting, users.summary(meeting.getHostId()), myRole);
    }

    /**
     * The meeting as returned by join / join-status, whose caller may be an anonymous guest or a signed-in
     * stranger still waiting: the host's e-mail address is withheld ({@code host.email = null}). Members get it
     * from {@code GET /meetings/{id}}.
     */
    @Transactional(readOnly = true)
    public MeetingView joinView(Meeting meeting, MeetingRole myRole) {
        return mapper.toView(meeting, users.summary(meeting.getHostId()).withoutEmail(), myRole);
    }

    /** Creates a scheduled meeting on behalf of the booking module (no rate limit, host is the page owner). */
    @Transactional
    public Meeting createForBooking(UUID hostId, String title, String description, Instant start, Instant end,
                                    String timeZone, com.example.meetplatform.meeting.entity.MeetingEnums.MediaMode mediaMode,
                                    String inviteeEmail) {
        Meeting meeting = new Meeting(newJoinCode(), title, MeetingType.SCHEDULED, hostId, timeZone);
        meeting.setDescription(description);
        meeting.setStartTime(start);
        meeting.setEndTime(end);
        meeting.setMediaMode(mediaMode);
        meeting.setWaitingRoomEnabled(true);
        meeting.setMaxParticipants(props.defaultMaxParticipants());
        meetings.save(meeting);
        invite(meeting, List.of(inviteeEmail), null, null);
        return meeting;
    }

    // ------------------------------------------------------------------ helpers

    /** Creates invitations for new addresses; {@code limitedHostId} (null = system-initiated) pays the e-mail budget. */
    private List<UUID> invite(Meeting meeting, List<String> emails, String hostEmail, UUID limitedHostId) {
        if (emails == null || emails.isEmpty()) {
            return List.of();
        }
        Set<String> existing = new HashSet<>(invitations.findEmails(meeting.getId()));
        Set<String> unique = new LinkedHashSet<>();
        for (String e : emails) {
            String normalized = e.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !existing.contains(normalized) && !normalized.equalsIgnoreCase(hostEmail)) {
                unique.add(normalized);
            }
        }
        if (limitedHostId != null) {
            // Every new invitation sends an e-mail: cap them per host so the API cannot be used to spam.
            rateLimiter.consume("meeting-invitations", limitedHostId.toString(), unique.size(), INVITATIONS_PER_HOUR,
                    Duration.ofHours(1));
        }
        Instant now = clock.instant();
        List<UUID> created = new ArrayList<>();
        for (String email : unique) {
            UUID userId = users.findByEmail(email).map(User::getId).orElse(null);
            created.add(invitations.save(new MeetingInvitation(meeting.getId(), email, userId, now)).getId());
        }
        return created;
    }

    private void applyOrganization(Meeting meeting, CurrentUser user, UUID organizationId, Privacy privacy) {
        if (organizationId != null) {
            if (!organizations.isMember(organizationId, user.userId())) {
                throw ApiException.forbidden("You are not a member of this organization.");
            }
            meeting.setOrganizationId(organizationId);
            if (!organizationAllowsGuests(meeting)) {
                meeting.setAllowGuests(false);
            }
        }
        Privacy effective = privacy == null ? Privacy.LINK : privacy;
        if (effective == Privacy.ORGANIZATION && organizationId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "ORGANIZATION privacy requires organizationId.");
        }
        meeting.setPrivacy(effective);
        if (effective != Privacy.LINK) {
            meeting.setAllowGuests(false);
        }
    }

    private boolean organizationAllowsGuests(Meeting meeting) {
        return organizations.find(meeting.getOrganizationId()).map(o -> o.isAllowGuests()).orElse(true);
    }

    private int capacity(Integer requested) {
        int value = requested == null ? props.defaultMaxParticipants() : requested;
        return Math.min(Math.max(2, value), props.hardMaxParticipants());
    }

    private String newJoinCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = Tokens.randomCode(JOIN_CODE_LENGTH);
            if (!meetings.existsByJoinCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not allocate a unique join code");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
