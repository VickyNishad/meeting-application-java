package com.example.meetplatform.moderation.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditLog;
import com.example.meetplatform.common.audit.AuditLogRepository;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.response.PageParams;
import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
import com.example.meetplatform.identity.service.UserService;
import com.example.meetplatform.media.dto.MediaParticipant;
import com.example.meetplatform.media.dto.MediaPermissions;
import com.example.meetplatform.media.dto.TrackSource;
import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.media.service.RoomNames;
import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.dto.MeetingDtos.MeetingView;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaKind;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaPermission;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.meeting.service.MeetingAccessService;
import com.example.meetplatform.meeting.service.MeetingPermissions;
import com.example.meetplatform.meeting.service.MeetingService;
import com.example.meetplatform.moderation.dto.ModerationDtos.AuditEntry;
import com.example.meetplatform.moderation.dto.ModerationDtos.MediaOverride;
import com.example.meetplatform.moderation.dto.ModerationDtos.MediaPermissionsRequest;
import com.example.meetplatform.moderation.dto.ModerationDtos.ParticipantMediaRequest;
import com.example.meetplatform.moderation.dto.ModerationDtos.MuteSource;
import com.example.meetplatform.moderation.dto.ModerationDtos.SettingsRequest;
import com.example.meetplatform.moderation.dto.ModerationDtos.UnmuteSource;
import com.example.meetplatform.participant.dto.ParticipantDtos.ParticipantView;
import com.example.meetplatform.participant.dto.RoomStateDtos.MediaPermissionsView;
import com.example.meetplatform.participant.dto.RoomStateDtos.ParticipantMedia;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.entity.MeetingParticipant.State;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.participant.service.MediaGrantSync;
import com.example.meetplatform.participant.service.ParticipantPolicy;
import com.example.meetplatform.participant.service.RoomAudience;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.service.RoomSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Host controls. Every action is authorized server-side against the caller's live role, applied to the media
 * server through its admin API, and written to the audit log. There is deliberately no "unmute" operation:
 * the server can only ask, the participant's own client must re-enable the track.
 *
 * <p>Media-server (HTTP) calls never run while the meeting row lock and a database connection are held: state
 * changes commit first, then the SFU is called outside the transaction and a failure is reported to the caller
 * (the committed state is authoritative and the action can be retried; reconnects are re-checked by the
 * {@code participant_joined} webhook). Read-only actions (waiting list, audit trail, mutes) take no row lock.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final MeetingRepository meetings;
    private final MeetingService meetingService;
    private final MeetingAccessService access;
    private final MeetingPermissions permissions;
    private final MeetingParticipantRepository participants;
    private final RoomSessionService roomSessions;
    private final ParticipantPolicy policy;
    private final MediaService media;
    private final RoomSignals signals;
    private final AuditService audit;
    private final AuditLogRepository auditLogs;
    private final UserService users;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final RoomAudience audience;
    private final Clock clock;
    private final MediaGrantSync grants;

    public ModerationService(MeetingRepository meetings, MeetingService meetingService, MeetingAccessService access,
                             MeetingPermissions permissions, MeetingParticipantRepository participants,
                             RoomSessionService roomSessions, ParticipantPolicy policy, MediaService media,
                             RoomSignals signals, AuditService audit, AuditLogRepository auditLogs, UserService users,
                             TransactionTemplate tx, RoomAudience audience, Clock clock, MediaGrantSync grants) {
        this.grants = grants;
        this.audience = audience;
        this.meetings = meetings;
        this.meetingService = meetingService;
        this.access = access;
        this.permissions = permissions;
        this.participants = participants;
        this.roomSessions = roomSessions;
        this.policy = policy;
        this.media = media;
        this.signals = signals;
        this.audit = audit;
        this.auditLogs = auditLogs;
        this.users = users;
        this.tx = tx;
        this.readTx = new TransactionTemplate(tx.getTransactionManager());
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ waiting room

    /** Polled by the host's client every few seconds: a plain read, no meeting row lock. */
    @Transactional(readOnly = true)
    public List<ParticipantView> waiting(UUID meetingId, CurrentUser user) {
        ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, false, false);
        return participants.findByMeetingIdAndStateOrderByCreatedAtAsc(ctx.meeting.getId(), State.WAITING).stream()
                .map(ParticipantView::of).toList();
    }

    @Transactional
    public void admit(UUID meetingId, UUID participantId, CurrentUser user) {
        ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, true);
        MeetingParticipant p = target(ctx, participantId);
        if (p.getState() != State.WAITING) {
            throw new ApiException(ErrorCode.CONFLICT, "This participant is not waiting.");
        }
        ensureCapacity(ctx, 1);
        p.admit(ctx.room.getId(), clock.instant());
        audit.record(user.userId(), null, AuditActions.MOD_ADMIT, AuditActions.RESOURCE_MEETING, meetingId,
                Map.of("participantId", participantId));
    }

    @Transactional
    public void deny(UUID meetingId, UUID participantId, CurrentUser user) {
        ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, false);
        MeetingParticipant p = target(ctx, participantId);
        if (p.getState() != State.WAITING) {
            throw new ApiException(ErrorCode.CONFLICT, "This participant is not waiting.");
        }
        p.deny(clock.instant());
        audit.record(user.userId(), null, AuditActions.MOD_DENY, AuditActions.RESOURCE_MEETING, meetingId,
                Map.of("participantId", participantId));
    }

    @Transactional
    public void admitAll(UUID meetingId, CurrentUser user) {
        ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, true);
        List<MeetingParticipant> waiting = participants.findByMeetingIdAndStateOrderByCreatedAtAsc(meetingId, State.WAITING);
        ensureCapacity(ctx, waiting.size());
        Instant now = clock.instant();
        waiting.forEach(p -> p.admit(ctx.room.getId(), now));
        audit.record(user.userId(), null, AuditActions.MOD_ADMIT, AuditActions.RESOURCE_MEETING, meetingId,
                Map.of("count", waiting.size()));
    }

    // ------------------------------------------------------------------ audio / video

    /** Muting changes no database state: authorize without the row lock, call the SFU outside any transaction. */
    public void mute(UUID meetingId, UUID participantId, MuteSource source, CurrentUser user) {
        String[] target = readTx.execute(s -> {
            ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, true, false);
            MeetingParticipant p = presentTarget(ctx, participantId);
            if (p.getRole() == MeetingRole.HOST && ctx.role != MeetingRole.HOST) {
                throw ApiException.forbidden("Co-hosts cannot mute the host.");
            }
            return new String[] {publishingRoom(ctx, p), p.getId().toString()};
        });
        media.muteTrack(target[0], target[1], TrackSource.valueOf(source.name()));
        tx.executeWithoutResult(s -> audit.record(user.userId(), null, AuditActions.MOD_MUTE,
                AuditActions.RESOURCE_MEETING, meetingId, Map.of("participantId", participantId, "source", source)));
    }

    public int muteAll(UUID meetingId, CurrentUser user) {
        MuteAllTargets targets = readTx.execute(s -> {
            ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, true, false);
            Set<String> identities = participants.findByRoomIdAndStateIn(ctx.room.getId(), MeetingParticipant.PRESENT)
                    .stream().filter(p -> !p.getRole().isModerator()).map(p -> p.getId().toString())
                    .collect(Collectors.toSet());
            // Everyone muted here is a non-moderator: in HOST_AND_SELF_ONLY each publishes in its own audience room.
            return new MuteAllTargets(ctx.room.getLivekitRoomName(), ctx.meeting.isStageOnly(), identities);
        });
        // In a shared room one listing call tells us whose microphone is actually live, so we only touch those
        // participants instead of querying the SFU once per seat. Attendees in rooms of their own are muted one by
        // one (muting a microphone that is not published is a no-op).
        Set<String> liveMics = targets.perAttendeeRooms ? targets.identities
                : media.listParticipants(targets.roomName).stream()
                        .filter(mp -> mp.unmutedSources().contains(TrackSource.MICROPHONE))
                        .map(MediaParticipant::identity)
                        .collect(Collectors.toSet());
        int muted = 0;
        for (String identity : targets.identities) {
            if (!liveMics.contains(identity)) {
                continue;
            }
            String roomName = targets.perAttendeeRooms
                    ? RoomNames.audience(targets.roomName, UUID.fromString(identity)) : targets.roomName;
            try {
                if (media.muteTrack(roomName, identity, TrackSource.MICROPHONE)) {
                    muted++;
                }
            } catch (ApiException e) {
                // Participant may have disconnected between listing and muting; keep going.
                log.debug("Mute-all skipped {}: {}", identity, e.getMessage());
            }
        }
        int count = muted;
        tx.executeWithoutResult(s -> audit.record(user.userId(), null, AuditActions.MOD_MUTE_ALL,
                AuditActions.RESOURCE_MEETING, meetingId, Map.of("muted", count)));
        return muted;
    }

    /** Asks the participant to turn a device on. Only their own client, with their consent, can do it. */
    @Transactional
    public void requestUnmute(UUID meetingId, UUID participantId, UnmuteSource source, CurrentUser user) {
        ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, true, false);
        MeetingParticipant p = presentTarget(ctx, participantId);
        signals.sendTo(ctx.room.getLivekitRoomName(), RoomSignals.TOPIC_MODERATION,
                Map.of("type", "request-unmute", "source", source.name()), List.of(p.getId().toString()));
        audit.record(user.userId(), null, AuditActions.MOD_REQUEST_UNMUTE, AuditActions.RESOURCE_MEETING, meetingId,
                Map.of("participantId", participantId, "source", source));
    }

    // ------------------------------------------------------------------ membership

    /**
     * Marks the participant REMOVED (committed under the row lock), then disconnects them on the SFU outside the
     * transaction. If the SFU call fails the caller gets 503 and may retry; the participant is already REMOVED,
     * so a reconnect is ejected by the webhook and they cannot re-join this session.
     */
    public void remove(UUID meetingId, UUID participantId, CurrentUser user) {
        String[] target = tx.execute(s -> {
            ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, true);
            MeetingParticipant p = target(ctx, participantId);
            if (p.getRole() == MeetingRole.HOST) {
                throw ApiException.forbidden("The host cannot be removed.");
            }
            if (p.getRole() == MeetingRole.CO_HOST && ctx.role != MeetingRole.HOST) {
                throw ApiException.forbidden("Only the host can remove a co-host.");
            }
            String roomName = ctx.room.getLivekitRoomName();
            signals.sendTo(roomName, RoomSignals.TOPIC_MODERATION, Map.of("type", "removed"), List.of(p.getId().toString()));
            boolean wasRecording = p.isRecordingLocally();
            boolean handRaised = p.isHandRaised();
            boolean wasModerator = p.getRole().isModerator();
            List<String> rooms = new ArrayList<>(List.of(roomName));
            rooms.addAll(audienceRoomsOf(ctx, p, wasModerator));
            p.remove(clock.instant());
            audience.announceDeparture(ctx.meeting, ctx.room, p, wasRecording, handRaised);
            participants.flush();
            audience.announcePresence(ctx.meeting, ctx.room, wasModerator);
            audit.record(user.userId(), null, AuditActions.MOD_REMOVE, AuditActions.RESOURCE_MEETING, meetingId,
                    Map.of("participantId", participantId));
            rooms.add(0, p.getId().toString());
            return rooms.toArray(String[]::new);
        });
        // Disconnected from every media room of the session (stage, and the audience rooms in HOST_AND_SELF_ONLY).
        ApiException failure = null;
        for (int i = 1; i < target.length; i++) {
            try {
                media.removeParticipant(target[i], target[0]);
            } catch (ApiException e) {
                log.warn("Could not disconnect {} from {}: {}", target[0], target[i], e.getMessage());
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * The audience rooms a participant of a HOST_AND_SELF_ONLY session is (or may be) connected to: its own as an
     * attendee, every present attendee's as host or co-host.
     */
    private List<String> audienceRoomsOf(ModeratorContext ctx, MeetingParticipant p, boolean moderator) {
        if (!ctx.meeting.isStageOnly()) {
            return List.of();
        }
        String stageRoom = ctx.room.getLivekitRoomName();
        if (!moderator) {
            return List.of(RoomNames.audience(stageRoom, p.getId()));
        }
        return participants.findByRoomIdAndStateIn(ctx.room.getId(), MeetingParticipant.PRESENT).stream()
                .filter(a -> !a.getRole().isModerator() && !a.getId().equals(p.getId()))
                .map(a -> RoomNames.audience(stageRoom, a.getId()))
                .toList();
    }

    public void changeRole(UUID meetingId, UUID participantId, MeetingRole role, CurrentUser user) {
        if (role != MeetingRole.CO_HOST && role != MeetingRole.PARTICIPANT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "role must be CO_HOST or PARTICIPANT.");
        }
        List<MediaUpdate> updates = tx.execute(s -> {
            ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.ASSIGN_ROLES, true);
            MeetingParticipant p = presentTarget(ctx, participantId);
            boolean wasModerator = p.getRole().isModerator();
            if (p.getRole() == MeetingRole.HOST) {
                throw ApiException.forbidden("The host's role cannot be changed.");
            }
            if (role == MeetingRole.CO_HOST && p.getUserId() == null) {
                throw new ApiException(ErrorCode.CONFLICT, "Guests must sign in before they can be made co-host.");
            }
            p.setRole(role);
            p.setMasked(ParticipantPolicy.isMasked(ctx.meeting, role));
            // Rooms of the attendees a demoted co-host was watching (computed before the role change is flushed).
            List<String> watched = ctx.meeting.isStageOnly() && wasModerator && !role.isModerator()
                    ? audienceRoomsOf(ctx, p, true) : List.of();
            Map<String, String> changed = Map.of("type", "role-changed", "participantId", participantId.toString(),
                    "role", role.name());
            participants.flush();
            if (ctx.meeting.isStageOnly() && !role.isModerator()) {
                // A demoted co-host becomes an attendee: other attendees must not learn that this person is still
                // here, so only the stage and the person themselves are told.
                signals.sendToStage(ctx.room.getLivekitRoomName(), RoomSignals.TOPIC_MODERATION, changed,
                        audience.stageIdentities(ctx.room.getId()), List.of(participantId.toString()));
            } else {
                signals.broadcast(ctx.room.getLivekitRoomName(), RoomSignals.TOPIC_MODERATION, changed);
            }
            if (ctx.meeting.isStageOnly() && wasModerator != role.isModerator()) {
                audience.announcePresence(ctx.meeting, ctx.room, true);
            }
            audit.record(user.userId(), null, AuditActions.MOD_ROLE_CHANGE, AuditActions.RESOURCE_MEETING, meetingId,
                    Map.of("participantId", participantId, "role", role));
            // Rights in every media room change at once (a demoted co-host stops seeing attendees now). In
            // HOST_AND_SELF_ONLY the client then reconnects with fresh tokens (its own role-changed signal), which
            // also moves it between hidden watcher and visible stage member.
            List<MediaUpdate> result = new ArrayList<>(mediaUpdates(ctx, List.of(p), false));
            watched.forEach(room -> result.add(MediaUpdate.disconnect(room, p.getId().toString())));
            return result;
        });
        applyMediaUpdates(updates);
        // The host's view of this person's media rights: a co-host has every right, a participant the meeting's and the
        // host's overrides.
        grants.announceToHost(meetingId, List.of(participantId));
    }

    // ------------------------------------------------------------------ room settings

    @Transactional
    public void lock(UUID meetingId, boolean locked, CurrentUser user) {
        ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, false);
        ctx.meeting.setLocked(locked);
        if (ctx.room != null) {
            signals.broadcast(ctx.room.getLivekitRoomName(), RoomSignals.TOPIC_MODERATION,
                    Map.of("type", "room-locked", "locked", locked));
        }
        audit.record(user.userId(), null, AuditActions.MOD_LOCK, AuditActions.RESOURCE_MEETING, meetingId,
                Map.of("locked", locked));
    }

    public MeetingView settings(UUID meetingId, SettingsRequest request, CurrentUser user) {
        boolean[] pushGrants = {false};
        MeetingView view = tx.execute(s -> {
            ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, false);
            Meeting meeting = ctx.meeting;
            boolean policyChanged = request.screenSharePolicy() != null
                    && request.screenSharePolicy() != meeting.getScreenSharePolicy();
            if (request.screenSharePolicy() != null) {
                // The meeting-wide screen-sharing permission: the host's, like the other media permissions.
                requireHost(ctx);
                meeting.setScreenSharePolicy(request.screenSharePolicy());
            }
            if (request.waitingRoomEnabled() != null) {
                meeting.setWaitingRoomEnabled(request.waitingRoomEnabled());
            }
            if (request.chatEnabled() != null) {
                meeting.setChatEnabled(request.chatEnabled());
            }
            if (ctx.room != null) {
                pushGrants[0] = policyChanged;
                signals.broadcast(ctx.room.getLivekitRoomName(), RoomSignals.TOPIC_MODERATION, Map.of("type", "settings",
                        "screenSharePolicy", meeting.getScreenSharePolicy().name(), "chatEnabled", meeting.isChatEnabled()));
            }
            audit.record(user.userId(), null, AuditActions.MOD_SETTINGS, AuditActions.RESOURCE_MEETING, meetingId,
                    Map.of("screenSharePolicy", meeting.getScreenSharePolicy(), "waitingRoomEnabled",
                            meeting.isWaitingRoomEnabled(), "chatEnabled", meeting.isChatEnabled()));
            return meetingService.view(meeting, ctx.role);
        });
        if (pushGrants[0]) {
            grants.everyone(meetingId);
        }
        return view;
    }

    // ------------------------------------------------------------------ media permissions (host only)

    /**
     * Meeting-wide microphone / camera / screen-sharing permissions. Host only. Saved under the meeting row lock (so
     * concurrent changes apply in order), then, from what is committed, pushed to every participant of the live
     * session on the SFU, which refuses to publish a source that is not allowed and unpublishes a track whose source
     * is no longer allowed.
     */
    public MediaPermissionsView mediaPermissions(UUID meetingId, MediaPermissionsRequest request, CurrentUser user) {
        if (request.microphone() == null && request.camera() == null && request.screenShare() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give at least one of microphone, camera, screenShare.");
        }
        MediaPermissionsView view = tx.execute(s -> {
            ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, false);
            requireHost(ctx);
            Meeting meeting = ctx.meeting;
            setIfGiven(meeting, MediaKind.MICROPHONE, request.microphone());
            setIfGiven(meeting, MediaKind.CAMERA, request.camera());
            setIfGiven(meeting, MediaKind.SCREEN_SHARE, request.screenShare());
            MediaPermissionsView changed = policy.mediaPermissions(meeting);
            audit.record(user.userId(), null, AuditActions.MOD_MEDIA_PERMISSIONS, AuditActions.RESOURCE_MEETING,
                    meetingId, Map.of("microphone", changed.microphone(), "camera", changed.camera(),
                            "screenShare", changed.screenShare()));
            return changed;
        });
        grants.everyone(meetingId);
        return view;
    }

    /**
     * One participant's media overrides (ALLOWED / RESTRICTED override the meeting's permission, DEFAULT removes the
     * override). Host only; the participant must be in this meeting's live session. Hosts and co-hosts always have
     * full media rights, so they take no overrides.
     */
    public ParticipantMedia participantMediaPermissions(UUID meetingId, UUID participantId, ParticipantMediaRequest request,
                                                        CurrentUser user) {
        if (request.microphone() == null && request.camera() == null && request.screenShare() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give at least one of microphone, camera, screenShare.");
        }
        ParticipantMedia view = tx.execute(s -> {
            ModeratorContext ctx = resolveModerator(meetingId, user, MeetingPermissions.MODERATE, true);
            requireHost(ctx);
            MeetingParticipant p = presentTarget(ctx, participantId);
            if (p.getRole().isModerator()) {
                throw new ApiException(ErrorCode.CONFLICT, "Hosts and co-hosts always have full media permissions.");
            }
            overrideIfGiven(p, MediaKind.MICROPHONE, request.microphone());
            overrideIfGiven(p, MediaKind.CAMERA, request.camera());
            overrideIfGiven(p, MediaKind.SCREEN_SHARE, request.screenShare());
            ParticipantMedia changed = policy.participantMedia(ctx.meeting, p);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("participantId", participantId);
            details.put("microphone", request.microphone());
            details.put("camera", request.camera());
            details.put("screenShare", request.screenShare());
            details.values().removeIf(Objects::isNull);
            audit.record(user.userId(), null, AuditActions.MOD_PARTICIPANT_MEDIA, AuditActions.RESOURCE_MEETING,
                    meetingId, details);
            return changed;
        });
        grants.participants(meetingId, List.of(participantId), true);
        return view;
    }

    private static void requireHost(ModeratorContext ctx) {
        if (ctx.role != MeetingRole.HOST) {
            throw ApiException.forbidden("Only the host can change media permissions.");
        }
    }

    private static void setIfGiven(Meeting meeting, MediaKind kind, MediaPermission permission) {
        if (permission != null) {
            meeting.setMediaPermission(kind, permission);
        }
    }

    private static void overrideIfGiven(MeetingParticipant p, MediaKind kind, MediaOverride override) {
        if (override != null) {
            p.setMediaOverride(kind, switch (override) {
                case ALLOWED -> MediaPermission.ALLOWED;
                case RESTRICTED -> MediaPermission.RESTRICTED;
                case DEFAULT -> null;
            });
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> auditTrail(UUID meetingId, CurrentUser user, Integer page, Integer size) {
        resolveModerator(meetingId, user, MeetingPermissions.MODERATE, false, false);
        Page<AuditLog> logs = auditLogs.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
                AuditActions.RESOURCE_MEETING, meetingId, PageParams.of(page, size, Sort.unsorted()));
        Map<UUID, UserSummary> actors = users.summaries(logs.getContent().stream()
                .map(AuditLog::getActorUserId).filter(Objects::nonNull).distinct().toList());
        return PageResponse.of(logs, l -> new AuditEntry(l.getId(), l.getAction(),
                l.getActorUserId() == null ? null : actors.get(l.getActorUserId()), l.getActorParticipantId(),
                l.getDetails(), l.getCreatedAt()));
    }

    // ------------------------------------------------------------------ authorization helpers

    /**
     * Resolves the caller's live meeting role. The host is always a moderator; a co-host only while present
     * in the current session. The meeting row is locked, serializing state-changing calls of one meeting.
     */
    public ModeratorContext resolveModerator(UUID meetingId, CurrentUser user, String permission, boolean requireLiveRoom) {
        return resolveModerator(meetingId, user, permission, requireLiveRoom, true);
    }

    private ModeratorContext resolveModerator(UUID meetingId, CurrentUser user, String permission,
                                              boolean requireLiveRoom, boolean lockMeeting) {
        Meeting meeting = (lockMeeting ? meetings.findByIdForUpdate(meetingId) : meetings.findById(meetingId))
                .orElseThrow(() -> ApiException.notFound("Meeting"));
        MeetingRoom room = roomSessions.active(meetingId).orElse(null);
        MeetingRole role;
        if (access.isHost(meeting, user.userId())) {
            role = MeetingRole.HOST;
        } else {
            role = room == null ? null : participants.findByRoomIdAndStateIn(room.getId(), MeetingParticipant.PRESENT)
                    .stream().filter(p -> user.userId().equals(p.getUserId())).map(MeetingParticipant::getRole)
                    .findFirst().orElse(null);
        }
        if (role == null || !permissions.has(role, permission)) {
            access.requireView(meeting, user);
            throw ApiException.forbidden("Host or co-host privileges are required.");
        }
        if (requireLiveRoom && room == null) {
            throw new ApiException(ErrorCode.MEETING_NOT_ACTIVE, "The meeting is not live.");
        }
        return new ModeratorContext(meeting, room, role);
    }

    private MeetingParticipant target(ModeratorContext ctx, UUID participantId) {
        return participants.findByIdAndMeetingId(participantId, ctx.meeting.getId())
                .orElseThrow(() -> ApiException.notFound("Participant"));
    }

    private MeetingParticipant presentTarget(ModeratorContext ctx, UUID participantId) {
        MeetingParticipant p = target(ctx, participantId);
        if (!p.isPresent() || !ctx.room.getId().equals(p.getRoomId())) {
            throw new ApiException(ErrorCode.CONFLICT, "This participant is not in the meeting.");
        }
        return p;
    }

    private void ensureCapacity(ModeratorContext ctx, int additional) {
        long seated = participants.countOccupiedSeats(ctx.room.getId(), clock.instant().minus(java.time.Duration.ofMinutes(2)));
        if (seated + additional > ctx.meeting.getMaxParticipants()) {
            throw new ApiException(ErrorCode.MEETING_FULL, "Admitting would exceed the participant limit.");
        }
    }

    /** Grants to push to connected participants; participants not connected yet get them with their next token. */
    private List<MediaUpdate> mediaUpdates(ModeratorContext ctx, List<MeetingParticipant> targets, boolean rename) {
        String stageRoom = ctx.room.getLivekitRoomName();
        List<MediaUpdate> updates = new ArrayList<>();
        for (MeetingParticipant p : targets) {
            if (p.getState() != State.JOINED) {
                continue;
            }
            String metadata = policy.metadataFor(ctx.meeting, p);
            updates.add(new MediaUpdate(stageRoom, p.getId().toString(), metadata,
                    policy.permissionsFor(ctx.meeting, p), rename ? policy.visibleName(ctx.meeting, p) : null));
            if (ctx.meeting.isStageOnly()) {
                // Its own audience room: an attendee publishes there; once promoted it may only watch.
                updates.add(new MediaUpdate(RoomNames.audience(stageRoom, p.getId()), p.getId().toString(), metadata,
                        policy.audiencePermissionsFor(ctx.meeting, p), null));
            }
        }
        return updates;
    }

    /** The media room a participant publishes in: its own audience room for an attendee of a HOST_AND_SELF_ONLY session. */
    private static String publishingRoom(ModeratorContext ctx, MeetingParticipant p) {
        String stageRoom = ctx.room.getLivekitRoomName();
        return ctx.meeting.isStageOnly() && !p.getRole().isModerator()
                ? RoomNames.audience(stageRoom, p.getId()) : stageRoom;
    }

    /** Applies committed permission changes on the SFU; tries every participant, then reports any failure. */
    private void applyMediaUpdates(List<MediaUpdate> updates) {
        ApiException failure = null;
        for (MediaUpdate u : updates) {
            try {
                if (u.disconnect) {
                    media.removeParticipant(u.roomName, u.identity);
                    continue;
                }
                media.updateParticipant(u.roomName, u.identity, u.metadata, u.permissions);
                if (u.name != null) {
                    media.renameParticipant(u.roomName, u.identity, u.name);
                }
            } catch (ApiException e) {
                log.warn("Could not update media permissions of {}: {}", u.identity, e.getMessage());
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** {@code roomName}: the shared room, or the stage room when every attendee has an audience room of its own. */
    private record MuteAllTargets(String roomName, boolean perAttendeeRooms, Set<String> identities) {
    }

    /** {@code name}: new visible name to set, or null to leave it. {@code disconnect}: remove from the room instead. */
    private record MediaUpdate(String roomName, String identity, String metadata, MediaPermissions permissions,
                               String name, boolean disconnect) {

        MediaUpdate(String roomName, String identity, String metadata, MediaPermissions permissions, String name) {
            this(roomName, identity, metadata, permissions, name, false);
        }

        static MediaUpdate disconnect(String roomName, String identity) {
            return new MediaUpdate(roomName, identity, null, null, null, true);
        }
    }

    public record ModeratorContext(Meeting meeting, MeetingRoom room, MeetingRole role) {
    }
}
