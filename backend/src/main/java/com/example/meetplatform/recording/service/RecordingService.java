package com.example.meetplatform.recording.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.common.response.PageParams;
import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.scheduling.JobLock;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.storage.ObjectStorage;
import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
import com.example.meetplatform.identity.service.UserService;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.media.dto.MediaWebhookEvent.EgressUpdate;
import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.service.MeetingAccessService;
import com.example.meetplatform.meeting.service.MeetingParticipationLookup;
import com.example.meetplatform.meeting.service.MeetingPermissions;
import com.example.meetplatform.meeting.service.MeetingService;
import com.example.meetplatform.moderation.service.ModerationService;
import com.example.meetplatform.moderation.service.ModerationService.ModeratorContext;
import com.example.meetplatform.organization.service.OrganizationService;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.service.ParticipantAccessService;
import com.example.meetplatform.participant.service.RoomAudience;
import com.example.meetplatform.recording.dto.RecordingDtos.LocalState;
import com.example.meetplatform.recording.dto.RecordingDtos.RecordingView;
import com.example.meetplatform.recording.dto.RecordingDtos.SignedUrl;
import com.example.meetplatform.recording.dto.RecordingDtos.StartRecordingRequest;
import com.example.meetplatform.recording.entity.Recording;
import com.example.meetplatform.recording.entity.RecordingAccessLog;
import com.example.meetplatform.recording.repository.RecordingAccessLogRepository;
import com.example.meetplatform.recording.repository.RecordingRepository;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.event.RoomEvents;
import com.example.meetplatform.room.service.RoomSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cloud recording lifecycle (egress), playback/download through short-lived signed URLs, retention, and
 * local-recording notices. Recording state is always broadcast to the room so every participant sees it.
 */
@Service
public class RecordingService {

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm").withZone(ZoneOffset.UTC);

    private final RecordingRepository recordings;
    private final RecordingAccessLogRepository accessLogs;
    private final ModerationService moderation;
    private final MeetingService meetings;
    private final MeetingAccessService meetingAccess;
    private final MeetingParticipationLookup participation;
    private final OrganizationService organizations;
    private final RoomSessionService roomSessions;
    private final ParticipantAccessService participantAccess;
    private final MediaService media;
    private final RoomSignals signals;
    private final ObjectStorage storage;
    private final UserService users;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final AppProperties properties;
    private final RoomAudience audience;
    private final JobLock jobLock;
    private final Clock clock;

    public RecordingService(RecordingRepository recordings, RecordingAccessLogRepository accessLogs,
                            ModerationService moderation, MeetingService meetings, MeetingAccessService meetingAccess,
                            MeetingParticipationLookup participation, OrganizationService organizations,
                            RoomSessionService roomSessions, ParticipantAccessService participantAccess,
                            MediaService media, RoomSignals signals, ObjectStorage storage, UserService users,
                            AuditService audit, TransactionTemplate tx, AppProperties properties,
                            RoomAudience audience, JobLock jobLock, Clock clock) {
        this.audience = audience;
        this.jobLock = jobLock;
        this.recordings = recordings;
        this.accessLogs = accessLogs;
        this.moderation = moderation;
        this.meetings = meetings;
        this.meetingAccess = meetingAccess;
        this.participation = participation;
        this.organizations = organizations;
        this.roomSessions = roomSessions;
        this.participantAccess = participantAccess;
        this.media = media;
        this.signals = signals;
        this.storage = storage;
        this.users = users;
        this.audit = audit;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ cloud recording control

    @Transactional(noRollbackFor = ApiException.class)
    public RecordingView start(UUID meetingId, StartRecordingRequest request, CurrentUser user) {
        ModeratorContext ctx = moderation.resolveModerator(meetingId, user, MeetingPermissions.RECORD, true);
        MeetingRoom room = ctx.room();
        if (!recordings.findByRoomIdAndKindAndStatusIn(room.getId(), Recording.KIND_CLOUD, Recording.IN_FLIGHT)
                .isEmpty()) {
            throw new ApiException(ErrorCode.RECORDING_ALREADY_ACTIVE, "A cloud recording is already running.");
        }
        boolean audioOnly = Boolean.TRUE.equals(request.audioOnly())
                || ctx.meeting().getMediaMode() == com.example.meetplatform.meeting.entity.MeetingEnums.MediaMode.AUDIO_ONLY;
        String layout = request.layout() == null ? "grid" : request.layout();
        Recording recording = new Recording(meetingId, room.getId(), layout, audioOnly, user.userId(), clock.instant());
        recording.setStorageKey(properties.recording().filePrefix() + "/" + meetingId + "/" + recording.getId()
                + (audioOnly ? ".ogg" : ".mp4"));
        recording.describeCapture(ctx.meeting().getVisibilityMode().name(), ctx.meeting().isStageOnly());
        recordings.saveAndFlush(recording);

        // Consent: the indicator must reach the room before media capture can begin, so it is delivered now
        // (not after commit) and capture does not start if the room cannot be notified.
        try {
            signals.broadcastNow(room.getLivekitRoomName(), RoomSignals.TOPIC_RECORDING,
                    recordingSignal("CLOUD", "STARTED", null));
        } catch (ApiException e) {
            recording.fail("Could not notify the room: " + e.getMessage(), clock.instant());
            audit.record(user.userId(), null, AuditActions.RECORDING_FAILED, AuditActions.RESOURCE_RECORDING,
                    recording.getId(), Map.of("meetingId", meetingId, "stage", "NOTIFY"));
            throw e;
        }
        try {
            recording.setEgressId(media.startRoomRecording(room.getLivekitRoomName(), recording.getStorageKey(),
                    layout, audioOnly));
        } catch (ApiException e) {
            recording.fail("Could not start recording: " + e.getMessage(), clock.instant());
            signals.broadcast(room.getLivekitRoomName(), RoomSignals.TOPIC_RECORDING, recordingSignal("CLOUD", "FAILED", null));
            audit.record(user.userId(), null, AuditActions.RECORDING_FAILED, AuditActions.RESOURCE_RECORDING,
                    recording.getId(), Map.of("meetingId", meetingId, "stage", "START"));
            throw e;
        }
        audit.record(user.userId(), null, AuditActions.RECORDING_STARTED, AuditActions.RESOURCE_RECORDING,
                recording.getId(), Map.of("meetingId", meetingId, "layout", layout, "audioOnly", audioOnly));
        return view(recording, ctx.meeting());
    }

    @Transactional
    public RecordingView stop(UUID meetingId, UUID recordingId, CurrentUser user) {
        ModeratorContext ctx = moderation.resolveModerator(meetingId, user, MeetingPermissions.RECORD, false);
        Recording recording = recordings.findById(recordingId).filter(r -> r.getMeetingId().equals(meetingId))
                .orElseThrow(() -> ApiException.notFound("Recording"));
        if (!recording.isInFlight() || recording.getEgressId() == null) {
            throw new ApiException(ErrorCode.RECORDING_NOT_ACTIVE, "This recording is not running.");
        }
        media.stopRecording(recording.getEgressId());
        recording.setStatus(Recording.Status.STOPPING);
        if (recording.isAutoStarted()) {
            // A moderator's stop holds: the next join must not start this recording again.
            recording.suppressAutoRestart();
        }
        if (ctx.room() != null) {
            signals.broadcast(ctx.room().getLivekitRoomName(), RoomSignals.TOPIC_RECORDING,
                    RecordingSignals.of(recording, "STOPPED"));
        }
        audit.record(user.userId(), null, AuditActions.RECORDING_STOPPED, AuditActions.RESOURCE_RECORDING, recordingId,
                Map.of("meetingId", meetingId));
        return view(recording, ctx.meeting());
    }

    /**
     * A participant's client started/stopped a local recording: everyone who can appear in it must be told (in a
     * webinar an attendee's recording can only show the stage, so the stage is told). The notice is delivered
     * synchronously; if the room cannot be notified this fails with 503 MEDIA_PROVIDER_UNAVAILABLE and the client
     * must abort the local recording. The state is kept on the participant so late joiners see it too.
     */
    @Transactional
    public void localRecordingNotice(UUID meetingId, UUID participantId, LocalState state, String token,
                                     Optional<CurrentUser> user) {
        MeetingParticipant participant = participantAccess.resolvePresent(meetingId, participantId, token, user);
        MeetingRoom room = roomSessions.active(meetingId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEETING_NOT_ACTIVE, "The meeting is not live."));
        Meeting meeting = meetings.require(meetingId);
        participant.setLocalRecording(state == LocalState.STARTED, clock.instant());
        audience.announceNow(meeting, room, RoomSignals.TOPIC_RECORDING,
                recordingSignal("LOCAL", state.name(), participant), participant);
        audit.record(participant.getUserId(), participant.getId(),
                state == LocalState.STARTED ? AuditActions.RECORDING_LOCAL_STARTED : AuditActions.RECORDING_LOCAL_STOPPED,
                AuditActions.RESOURCE_MEETING, meetingId, Map.of("participantId", participantId));
    }

    // ------------------------------------------------------------------ queries & access

    /**
     * The meeting's recordings the caller may see. The host sees all of them. A co-host who is in the live session
     * right now (and controls recording there) also sees that session's recordings, nothing from earlier sessions;
     * otherwise only what {@link #canView} allows.
     */
    @Transactional(readOnly = true)
    public List<RecordingView> forMeeting(UUID meetingId, CurrentUser user) {
        Meeting meeting = meetings.require(meetingId);
        meetingAccess.requireView(meeting, user);
        boolean manager = canManage(meeting, user);
        UUID coHostRoom = manager ? null : participation.presentRole(meetingId, user.userId())
                .filter(role -> role == MeetingRole.CO_HOST)
                .flatMap(role -> roomSessions.active(meetingId)).map(MeetingRoom::getId).orElse(null);
        return recordings.findByMeetingIdAndStatusNotOrderByCreatedAtDesc(meetingId, Recording.Status.DELETED).stream()
                .filter(r -> manager || (coHostRoom != null && coHostRoom.equals(r.getRoomId()))
                        || canView(r, meeting, user))
                .map(r -> view(r, meeting)).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<RecordingView> library(CurrentUser user, Integer page, Integer size) {
        var result = recordings.findLibrary(user.userId(), PageParams.of(page, size, Sort.unsorted()));
        return PageResponse.of(result, r -> view(r, meetings.require(r.getMeetingId())));
    }

    @Transactional(readOnly = true)
    public RecordingView get(UUID recordingId, CurrentUser user) {
        Recording recording = requireViewable(recordingId, user);
        return view(recording, meetings.require(recording.getMeetingId()));
    }

    @Transactional
    public SignedUrl playbackUrl(UUID recordingId, CurrentUser user, ClientInfo client) {
        Recording recording = requireViewable(recordingId, user);
        requireAvailable(recording);
        ObjectStorage.PresignedUrl url = storage.presignGet(properties.storage().recordingsBucket(),
                recording.getStorageKey(), properties.storage().presignTtl(), null);
        accessLogs.save(new RecordingAccessLog(recordingId, user.userId(), "PLAYBACK", client.ipAddress(),
                client.userAgent(), clock.instant()));
        return new SignedUrl(url.url(), url.expiresAt());
    }

    /** The host or an organization admin, and the person a participant recording shows (their own file). */
    @Transactional
    public SignedUrl downloadUrl(UUID recordingId, CurrentUser user, ClientInfo client) {
        Recording recording = requireViewable(recordingId, user);
        Meeting meeting = meetings.require(recording.getMeetingId());
        if (!canManage(meeting, user) && !(recording.isParticipantRecording()
                && user.userId().equals(recording.getSubjectUserId()))) {
            throw ApiException.forbidden("Only the host or an organization admin can do this.");
        }
        requireAvailable(recording);
        String subject = recording.isParticipantRecording() && recording.getSubjectName() != null
                ? "_" + safeFileName(recording.getSubjectName()) : "";
        String fileName = safeFileName(meeting.getTitle()) + subject + "_" + FILE_DATE.format(recording.getStartedAt())
                + (recording.isAudioOnly() && !recording.isParticipantRecording() ? ".ogg" : ".mp4");
        ObjectStorage.PresignedUrl url = storage.presignGet(properties.storage().recordingsBucket(),
                recording.getStorageKey(), properties.storage().presignTtl(), fileName);
        accessLogs.save(new RecordingAccessLog(recordingId, user.userId(), "DOWNLOAD", client.ipAddress(),
                client.userAgent(), clock.instant()));
        return new SignedUrl(url.url(), url.expiresAt());
    }

    @Transactional
    public void delete(UUID recordingId, CurrentUser user, ClientInfo client) {
        Recording recording = requireManageable(recordingId, user);
        if (recording.isInFlight()) {
            throw new ApiException(ErrorCode.CONFLICT, "Stop the recording before deleting it.");
        }
        deleteObject(recording);
        recording.markDeleted(clock.instant());
        accessLogs.save(new RecordingAccessLog(recordingId, user.userId(), "DELETE", client.ipAddress(),
                client.userAgent(), clock.instant()));
        audit.record(AuditActions.RECORDING_DELETED, AuditActions.RESOURCE_RECORDING, recordingId,
                Map.of("meetingId", recording.getMeetingId()));
    }

    // ------------------------------------------------------------------ media events

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onMediaEvent(MediaWebhookEvent event) {
        if (event.egress() == null) {
            return;
        }
        EgressUpdate egress = event.egress();
        Optional<Recording> found = recordings.findByEgressId(egress.egressId());
        if (found.isEmpty()) {
            log.debug("Egress {} is not one of ours", egress.egressId());
            return;
        }
        Recording recording = found.get();
        if (recording.getStatus() == Recording.Status.AVAILABLE || recording.getStatus() == Recording.Status.DELETED) {
            return;
        }
        if (!recording.acceptEgressEvent(event.createdAt())) {
            log.debug("Ignoring out-of-date egress event for recording {}", recording.getId());
            return;
        }
        applyEgressState(recording, egress, "EGRESS");
    }

    /**
     * Applies an egress state (from a webhook or from reconciliation). Status only moves forward, so a delayed
     * "active" never turns a stopped or failed recording back on.
     */
    public void applyEgressState(Recording recording, EgressUpdate egress, String source) {
        Instant now = clock.instant();
        switch (egress.status()) {
            case STARTING -> {
            }
            case ACTIVE -> {
                Recording.Status before = recording.getStatus();
                recording.advanceTo(Recording.Status.ACTIVE);
                if (before != recording.getStatus() && egress.startedAt() != null) {
                    recording.setStartedAt(egress.startedAt());
                }
            }
            case ENDING -> recording.advanceTo(Recording.Status.STOPPING);
            case COMPLETE -> {
                if (recording.getStatus() == Recording.Status.FAILED) {
                    return;
                }
                Meeting meeting = meetings.require(recording.getMeetingId());
                Instant ended = egress.endedAt() != null ? egress.endedAt() : now;
                recording.complete(egress.sizeBytes(), egress.durationSeconds(), ended,
                        ended.plus(Duration.ofDays(retentionDays(meeting))));
            }
            case FAILED -> {
                if (recording.getStatus() == Recording.Status.FAILED) {
                    return;
                }
                // fail() also sets the retention of a possible partial file.
                recording.fail(egress.error() == null ? "Recording failed" : egress.error(), now);
                roomSessions.active(recording.getMeetingId()).filter(r -> r.getId().equals(recording.getRoomId()))
                        .ifPresent(room -> signals.broadcast(room.getLivekitRoomName(), RoomSignals.TOPIC_RECORDING,
                                RecordingSignals.of(recording, "FAILED")));
                audit.record(null, null, AuditActions.RECORDING_FAILED, AuditActions.RESOURCE_RECORDING,
                        recording.getId(), Map.of("meetingId", recording.getMeetingId(), "stage", source));
            }
        }
    }

    /** The room closed: egress finishes on its own; the final state arrives through egress webhooks. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSessionEnded(RoomEvents.SessionEnded event) {
        recordings.findByRoomIdAndStatusIn(event.roomId(), List.of(Recording.Status.STARTING, Recording.Status.ACTIVE))
                .forEach(r -> r.setStatus(Recording.Status.STOPPING));
    }

    /**
     * Daily retention enforcement, on one instance at a time: expired recordings (completed, and failed ones that may
     * have left a partial file) are removed from storage and marked deleted. A recording whose file cannot be deleted
     * now is retried the next day instead of blocking everything behind it.
     */
    @Scheduled(cron = "${app.recording.retention-cron:0 30 3 * * *}")
    public void enforceRetention() {
        jobLock.runExclusively("recording-retention", Duration.ofMinutes(30), this::deleteExpired);
    }

    private void deleteExpired() {
        List<Recording> expired;
        do {
            expired = tx.execute(s -> {
                Instant now = clock.instant();
                List<Recording> batch = recordings.findExpired(now, PageRequest.of(0, 50));
                for (Recording r : batch) {
                    try {
                        deleteObject(r);
                        r.markDeleted(now);
                        audit.record(null, null, AuditActions.RECORDING_DELETED, AuditActions.RESOURCE_RECORDING,
                                r.getId(), Map.of("reason", "RETENTION"));
                    } catch (ApiException e) {
                        log.warn("Retention delete failed for recording {}, retrying tomorrow: {}", r.getId(),
                                e.getMessage());
                        r.setRetentionUntil(now.plus(Duration.ofDays(1)));
                    }
                }
                return batch;
            });
        } while (expired != null && expired.size() == 50);
    }

    // ------------------------------------------------------------------ helpers

    private Recording requireViewable(UUID recordingId, CurrentUser user) {
        Recording recording = recordings.findById(recordingId)
                .filter(r -> r.getStatus() != Recording.Status.DELETED)
                .orElseThrow(() -> ApiException.notFound("Recording"));
        if (!canView(recording, meetings.require(recording.getMeetingId()), user)) {
            throw ApiException.notFound("Recording");
        }
        return recording;
    }

    /**
     * Managers see everything. A participant recording belongs to the signed-in user it shows (guests' recordings
     * stay with the host); auto-started room recordings are the host's; moderator-started room recordings are
     * shared with everyone who was admitted to the meeting.
     */
    private boolean canView(Recording recording, Meeting meeting, CurrentUser user) {
        if (canManage(meeting, user)) {
            return true;
        }
        if (recording.isParticipantRecording()) {
            return user.userId().equals(recording.getSubjectUserId());
        }
        return !recording.isAutoStarted() && participation.hasParticipated(meeting.getId(), user.userId());
    }

    private Recording requireManageable(UUID recordingId, CurrentUser user) {
        Recording recording = requireViewable(recordingId, user);
        if (!canManage(meetings.require(recording.getMeetingId()), user)) {
            throw ApiException.forbidden("Only the host or an organization admin can do this.");
        }
        return recording;
    }

    private boolean canManage(Meeting meeting, CurrentUser user) {
        return meetingAccess.isHost(meeting, user.userId()) || user.systemAdmin()
                || organizations.isAdmin(meeting.getOrganizationId(), user.userId());
    }

    private static void requireAvailable(Recording recording) {
        if (recording.getStatus() != Recording.Status.AVAILABLE) {
            throw new ApiException(ErrorCode.CONFLICT, "The recording is not available yet (status "
                    + recording.getStatus() + ").");
        }
    }

    /** Completed recordings have a file; failed ones may have a partial file (deleting a missing object is a no-op). */
    private void deleteObject(Recording recording) {
        if (recording.getStorageKey() != null && (recording.getStatus() == Recording.Status.AVAILABLE
                || recording.getStatus() == Recording.Status.FAILED)) {
            storage.delete(properties.storage().recordingsBucket(), recording.getStorageKey());
        }
    }

    private int retentionDays(Meeting meeting) {
        return organizations.find(meeting.getOrganizationId())
                .map(o -> o.getDefaultRecordingRetentionDays())
                .orElse(properties.recording().defaultRetentionDays());
    }

    private RecordingView view(Recording r, Meeting meeting) {
        UserSummary startedBy = r.getStartedBy() == null ? null : users.summaries(List.of(r.getStartedBy())).get(r.getStartedBy());
        return new RecordingView(r.getId(), r.getMeetingId(), meeting.getTitle(), r.getKind(), r.getStatus(),
                r.getStartedAt(), r.getEndedAt(), r.getDurationSeconds(), r.getSizeBytes(), r.getFailureReason(),
                r.getRetentionUntil(), startedBy, r.isAutoStarted(), r.getParticipantId(), r.getSubjectUserId(),
                r.getSubjectName(), r.getLayout(), r.getContentType(), r.isStageOnly());
    }

    private static Map<String, Object> recordingSignal(String kind, String state, MeetingParticipant participant) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "recording");
        payload.put("kind", kind);
        payload.put("state", state);
        if (participant != null) {
            payload.put("participantId", participant.getId().toString());
            payload.put("displayName", participant.getDisplayName());
        }
        return payload;
    }

    private static String safeFileName(String title) {
        String cleaned = title.replaceAll("[^A-Za-z0-9 _.-]", "").trim().replace(' ', '_');
        return cleaned.isEmpty() ? "recording" : cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}
