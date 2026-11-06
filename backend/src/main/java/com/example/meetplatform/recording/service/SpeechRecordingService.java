package com.example.meetplatform.recording.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.persistence.AfterCommit;
import com.example.meetplatform.common.response.PageParams;
import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.security.RateLimiter;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.event.MeetingEvents;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.service.ParticipantAccessService;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.AccessUrl;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.Facets;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.FinishRequest;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.MeetingOption;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.ParticipantOption;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.SpeechRecordingView;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.StartRequest;
import com.example.meetplatform.recording.entity.SpeechRecording;
import com.example.meetplatform.recording.repository.SpeechRecordingRepository;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.service.RoomSessionService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Speaking clips: each participant's browser records its own microphone (and camera) while the media server reports
 * it speaking, and streams the clip here in numbered chunks. The server decides everything about the file:
 * <ul>
 *   <li>where: {@code DD-MM-YYYY/<Meeting_Title>/<Participant_Name>/} under {@code RECORDING_STORAGE_PATH}, dates
 *   and the file's timestamp in the meeting's time zone (the zone its schedule uses), at the server's start time;</li>
 *   <li>the name: {@code DDMMYYHHmmss_<id>.webm} with a random id, so clips in the same second never collide and an
 *   existing file is never replaced;</li>
 *   <li>when it is complete: only after the file was finalized and verified as WebM (otherwise INCOMPLETE/FAILED).</li>
 * </ul>
 * Clips are only recorded in meetings with automatic recording on, where everyone is told before joining.
 *
 * <p>Access: the meeting's host sees every clip of its meetings; a signed-in participant sees its own. Files are only
 * served through signed URLs valid for minutes, issued after that check; the storage path never leaves the server.
 */
@Service
public class SpeechRecordingService {

    private static final Logger log = LoggerFactory.getLogger(SpeechRecordingService.class);
    private static final int RETENTION_BATCH = 50;
    /** Failing clips named per run in the log; the rest are counted in the summary. */
    private static final int RETENTION_FAILURES_LOGGED = 10;
    private static final UUID FIRST_ID = new UUID(0, 0);
    /** How far before the request a clip may be dated (the browser waits for a voice before asking). */
    static final Duration MAX_START_BACKDATE = Duration.ofSeconds(10);
    public static final String CONTENT_PATH = "/api/v1/speech-recordings/{id}/content";
    private static final DateTimeFormatter QUERY_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter FOLDER_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final int NAME_ATTEMPTS = 5;

    private final SpeechRecordingRepository recordings;
    private final MeetingRepository meetings;
    private final ParticipantAccessService participantAccess;
    private final RoomSessionService roomSessions;
    private final SpeechClipStorage storage;
    private final SpeechClipObjectStore objectStore;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final long maxChunkBytes;
    private final long maxClipBytes;
    private final Duration accessTtl;
    private final String publicUrl;
    private final byte[] signingKey;
    private final ApplicationEventPublisher events;
    private final int retentionDays;
    private final Duration retentionRunLimit;
    private final MeterRegistry meters;

    public SpeechRecordingService(SpeechRecordingRepository recordings, MeetingRepository meetings,
                                  ParticipantAccessService participantAccess, RoomSessionService roomSessions,
                                  SpeechClipStorage storage, SpeechClipObjectStore objectStore,
                                  RateLimiter rateLimiter, AuditService audit,
                                  TransactionTemplate tx, AppProperties properties, Clock clock,
                                  @Value("${app.speech-recordings.max-chunk-bytes:8388608}") long maxChunkBytes,
                                  @Value("${app.speech-recordings.max-clip-bytes:524288000}") long maxClipBytes,
                                  @Value("${app.speech-recordings.access-url-ttl:PT10M}") Duration accessTtl,
                                  ApplicationEventPublisher events,
                                  @Value("${app.speech-recordings.retention-days:90}") int retentionDays,
                                  @Value("${app.speech-recordings.retention-run-limit:PT20M}") Duration retentionRunLimit,
                                  MeterRegistry meters) {
        this.retentionRunLimit = retentionRunLimit;
        this.meters = meters;
        this.retentionDays = retentionDays;
        this.events = events;
        this.recordings = recordings;
        this.meetings = meetings;
        this.participantAccess = participantAccess;
        this.roomSessions = roomSessions;
        this.storage = storage;
        this.objectStore = objectStore;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.maxChunkBytes = maxChunkBytes;
        this.maxClipBytes = maxClipBytes;
        this.accessTtl = accessTtl;
        this.publicUrl = properties.publicUrl().replaceAll("/+$", "");
        // A key of its own: an access-token or object-storage signature can never double as a clip signature.
        this.signingKey = hmac(properties.security().jwtSecret().getBytes(StandardCharsets.UTF_8), "speech-recordings");
    }

    // ------------------------------------------------------------------ recording (the participant's browser)

    @Transactional
    public SpeechRecordingView start(UUID meetingId, StartRequest request, String token, Optional<CurrentUser> user) {
        // Admitted to the live session (ADMITTED or JOINED): a participant may start speaking before the media
        // server's "joined" notification has been processed, and it only ever records its own microphone.
        MeetingParticipant participant = participantAccess.resolvePresent(meetingId, request.participantId(), token, user);
        rateLimiter.check("speech-recording", participant.getId().toString(), 60, Duration.ofMinutes(1));
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        if (!meeting.isAutoRecord()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Speaking clips are only recorded in meetings with automatic recording on.");
        }
        MeetingRoom room = roomSessions.active(meetingId)
                .filter(r -> r.getId().equals(participant.getRoomId()))
                .orElseThrow(() -> new ApiException(ErrorCode.MEETING_NOT_ACTIVE, "The meeting is not live."));
        storage.requireFreeSpace();
        // A clip still open for this participant (its browser reloaded or lost the finish): keep what it has. Locked
        // and read fresh first, so a chunk still being written to it completes before it is closed.
        for (UUID openId : recordings.openClipIds(participant.getId())) {
            recordings.findByIdForUpdate(openId).filter(SpeechRecording::isRecording)
                    .ifPresent(open -> closeAbandoned(open, "A new recording started before this one was finished."));
        }
        recordings.flush();

        // Dated from when the browser began recording the turn (it asks only once it heard a voice), within bounds:
        // never in the future, never more than MAX_START_BACKDATE before the request.
        long ago = request.recordingStartedMsAgo() == null ? 0
                : Math.min(request.recordingStartedMsAgo(), MAX_START_BACKDATE.toMillis());
        Instant start = clock.instant().minusMillis(ago);
        ZoneId zone = zoneOf(meeting);
        String format = request.format().toLowerCase(Locale.ROOT).split(";")[0].strip();
        // The name: start time to the second plus 24 random bits; a name already on disk is skipped, and the unique
        // storage_path constraint is the last guard (two clips can never get the same file).
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            String uniqueId = HexFormat.of().formatHex(randomBytes(3));
            String path = storage.relativePath(start, zone, meeting.getTitle(), participant.getDisplayName(), uniqueId);
            if (storage.exists(path)) {
                continue;
            }
            SpeechRecording clip = new SpeechRecording(UUID.randomUUID(), meetingId, room.getId(), participant.getId(),
                    participant.getUserId(), meeting.getHostId(), meeting.getTitle(), participant.getDisplayName(),
                    SpeechClipStorage.fileName(start, zone, uniqueId), path, format, start);
            recordings.saveAndFlush(clip);
            // The browser opens a clip only once it hears a voice: the first one starts automatic recording of the meeting.
            events.publishEvent(new MeetingEvents.SpeechDetected(meetingId, participant.getId()));
            return SpeechRecordingView.of(clip);
        }
        throw new ApiException(ErrorCode.CONFLICT, "Could not reserve a file name for the recording. Try again.");
    }

    /**
     * Appends chunk {@code seq} (0, 1, 2, ...). A chunk that was already stored (a retry) is accepted and ignored; a
     * chunk after a missing one is refused, so the file never has a hole.
     */
    @Transactional
    public SpeechRecordingView appendChunk(UUID meetingId, UUID recordingId, UUID participantId, int seq,
                                           InputStream body, long contentLength, String token,
                                           Optional<CurrentUser> user) {
        MeetingParticipant participant = participantAccess.resolve(meetingId, participantId, token, user);
        rateLimiter.check("speech-recording-chunk", participant.getId().toString(), 600, Duration.ofMinutes(1));
        if (contentLength > maxChunkBytes) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, "Recording chunks must be at most " + maxChunkBytes + " bytes.");
        }
        SpeechRecording clip = ownClipForUpdate(meetingId, recordingId, participant);
        if (!clip.isRecording()) {
            throw new ApiException(ErrorCode.RECORDING_NOT_ACTIVE, "This recording is no longer being recorded.");
        }
        if (seq < clip.getChunksReceived()) {
            return SpeechRecordingView.of(clip);
        }
        if (seq > clip.getChunksReceived()) {
            throw new ApiException(ErrorCode.CONFLICT, "Expected chunk " + clip.getChunksReceived() + ", got " + seq + ".");
        }
        long size = storage.append(clip.getStoragePath(), new BoundedInputStream(body, maxChunkBytes),
                clip.getFileSize(), maxClipBytes);
        clip.chunkStored(size, clock.instant());
        return SpeechRecordingView.of(clip);
    }

    /**
     * Ends the clip. COMPLETED only when every chunk arrived and the file was finalized and verified; with chunks
     * missing it is kept as INCOMPLETE; with no usable media it is FAILED. Finishing twice returns the result again.
     */
    @Transactional
    public SpeechRecordingView finish(UUID meetingId, UUID recordingId, FinishRequest request, String token,
                                      Optional<CurrentUser> user) {
        MeetingParticipant participant = participantAccess.resolve(meetingId, request.participantId(), token, user);
        SpeechRecording clip = ownClipForUpdate(meetingId, recordingId, participant);
        if (!clip.isRecording()) {
            return SpeechRecordingView.of(clip);
        }
        Instant now = clock.instant();
        if (request.chunks() != clip.getChunksReceived()) {
            closeAbandoned(clip, "Only " + clip.getChunksReceived() + " of " + request.chunks() + " parts arrived.");
            return SpeechRecordingView.of(clip);
        }
        try {
            long size = storage.finish(clip.getStoragePath());
            publish(clip);
            clip.complete(size, now, Math.max(0, now.toEpochMilli() - clip.getStartTime().toEpochMilli()));
        } catch (ApiException e) {
            if (e.code() != ErrorCode.CONFLICT) {
                // Storage unavailable: left RECORDING, so a retry, or the recovery job, can still finalize it.
                throw e;
            }
            clip.fail(now, "No valid media was received.");
        }
        return SpeechRecordingView.of(clip);
    }

    /**
     * Clips still RECORDING with no chunk since {@code staleAfter} (the browser is gone, or the server restarted while
     * they were recorded): what arrived is kept as INCOMPLETE, or FAILED when nothing usable arrived. Never COMPLETED.
     * Each clip is closed in a transaction of its own, so one clip that cannot be closed never holds up the others.
     */
    public int recoverStale(Duration staleAfter) {
        int n = 0;
        for (SpeechRecording clip : recordings.findStale(clock.instant().minus(staleAfter))) {
            try {
                Boolean closed = tx.execute(status -> recordings.findByIdForUpdate(clip.getId())
                        .filter(SpeechRecording::isRecording)
                        .map(locked -> {
                            closeAbandoned(locked, "The recording was not finished (connection lost or server restarted).");
                            return true;
                        })
                        .orElse(false));
                if (Boolean.TRUE.equals(closed)) {
                    n++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not recover speaking clip {}: {}", clip.getId(), e.toString());
            }
        }
        return n;
    }

    /**
     * What one retention run did. {@code failed} clips could not be removed and {@code skipped} clips were not reached
     * before the run's time limit. Both stay as they are, and the next run tries them again.
     */
    public record RetentionRun(int deleted, int failed, long skipped) {
    }

    /**
     * Daily, S3 only: removes the objects of clips past their retention and marks their rows. The row stays as the
     * record of the clip; it is no longer listed or playable. The object goes first, so a crash in between only means
     * a repeat delete, which S3 accepts. A clip that cannot be removed is passed over, so it never holds up the clips
     * behind it. Only clips are touched here, and local clips are never removed.
     */
    public RetentionRun deleteExpired() {
        if (!objectStore.enabled() || retentionDays <= 0) {
            return new RetentionRun(0, 0, 0);
        }
        Instant start = clock.instant();
        Instant before = start.minus(Duration.ofDays(retentionDays));
        RetentionPass pass = new RetentionPass(start.plus(retentionRunLimit));
        while (Boolean.TRUE.equals(tx.execute(s -> removeNextBatch(before, pass)))) {
            // Each batch commits on its own, so one that succeeded is kept whatever happens later.
        }
        long skipped = pass.outOfTime ? recordings.countExpiredAfter(before, pass.afterTime, pass.afterId) : 0;
        meters.counter("meet.speech_clips.retention", "result", "deleted").increment(pass.deleted);
        meters.counter("meet.speech_clips.retention", "result", "failed").increment(pass.failed);
        meters.counter("meet.speech_clips.retention", "result", "skipped").increment(skipped);
        return new RetentionRun(pass.deleted, pass.failed, skipped);
    }

    /** @return whether there may be more clips to look at */
    private boolean removeNextBatch(Instant before, RetentionPass pass) {
        List<SpeechRecording> batch = recordings.findExpiredAfter(before, pass.afterTime, pass.afterId,
                PageRequest.of(0, RETENTION_BATCH));
        for (SpeechRecording clip : batch) {
            if (clock.instant().isAfter(pass.deadline)) {
                pass.outOfTime = true;
                return false;
            }
            pass.afterTime = clip.getStartTime();
            pass.afterId = clip.getId();
            try {
                objectStore.delete(clip.getStoragePath());
                clip.markDeleted(clock.instant());
                pass.deleted++;
            } catch (RuntimeException e) {
                pass.failed++;
                if (pass.failed <= RETENTION_FAILURES_LOGGED) {
                    log.warn("Retention could not remove speaking clip {}: {}", clip.getId(), e.getMessage());
                } else {
                    log.debug("Retention could not remove speaking clip {}: {}", clip.getId(), e.getMessage());
                }
            }
        }
        return batch.size() == RETENTION_BATCH;
    }

    /** Where a retention run has got to, and what it has done so far. */
    private static final class RetentionPass {
        private final Instant deadline;
        private Instant afterTime = Instant.EPOCH;
        private UUID afterId = FIRST_ID;
        private int deleted;
        private int failed;
        private boolean outOfTime;

        private RetentionPass(Instant deadline) {
            this.deadline = deadline;
        }
    }

    private void closeAbandoned(SpeechRecording clip, String reason) {
        long size;
        try {
            size = storage.salvage(clip.getStoragePath());
        } catch (ApiException e) {
            if (e.code() != ErrorCode.NOT_FOUND) {
                throw e;
            }
            // Its stored path is not a usable path on this server: there is no file to keep.
            size = -1;
        }
        Instant end = clip.getLastChunkAt() != null ? clip.getLastChunkAt() : clock.instant();
        if (size > 0) {
            publish(clip);
            clip.incomplete(size, end, reason);
        } else {
            clip.fail(end, "No valid media was received. " + reason);
        }
    }

    /**
     * With S3 storage: uploads the finalized local clip before its status changes, so a clip is never COMPLETED or
     * INCOMPLETE without its object. An upload failure throws STORAGE_UNAVAILABLE and leaves the clip RECORDING with
     * its local file (finish can be retried; the recovery job retries it). The staged copy is removed only once the
     * new status is committed. With local storage: nothing to do, the finalized file is the result.
     */
    private void publish(SpeechRecording clip) {
        if (!objectStore.enabled()) {
            return;
        }
        Path file = storage.readable(clip.getStoragePath());
        objectStore.upload(clip.getStoragePath(), file, clip.getFileFormat());
        AfterCommit.run(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Uploaded speaking clip {} could not be removed from staging: {}", clip.getId(), e.getMessage());
            }
        });
    }

    private SpeechRecording ownClipForUpdate(UUID meetingId, UUID recordingId, MeetingParticipant participant) {
        return recordings.findByIdForUpdate(recordingId)
                .filter(r -> r.getMeetingId().equals(meetingId) && r.getParticipantId().equals(participant.getId()))
                .orElseThrow(() -> ApiException.notFound("Recording"));
    }

    // ------------------------------------------------------------------ dashboard (signed-in users)

    /**
     * {@code view}: "hosted" (every clip of the meetings the user hosts) or "mine" (the clips of the user's own
     * participations). Filters: folder date (YYYY-MM-DD), meeting, participant.
     */
    @Transactional(readOnly = true)
    public PageResponse<SpeechRecordingView> list(CurrentUser user, String view, LocalDate date, UUID meetingId,
                                                  UUID participantId, Integer page, Integer size) {
        boolean hosted = isHosted(view);
        Specification<SpeechRecording> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get(hosted ? "hostId" : "participantUserId"), user.userId()));
            where.add(cb.isNull(root.get("deletedAt")));
            if (date != null) {
                where.add(cb.like(root.get("storagePath"), FOLDER_DATE.format(date) + "/%"));
            }
            if (meetingId != null) {
                where.add(cb.equal(root.get("meetingId"), meetingId));
            }
            if (participantId != null) {
                where.add(cb.equal(root.get("participantId"), participantId));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        var result = recordings.findAll(spec, PageParams.of(page, size, Sort.by(Sort.Direction.DESC, "startTime")));
        return PageResponse.of(result, SpeechRecordingView::of);
    }

    @Transactional(readOnly = true)
    public Facets facets(CurrentUser user, String view) {
        boolean hosted = isHosted(view);
        UUID id = user.userId();
        List<MeetingOption> meetingOptions = meetingOptions(hosted ? recordings.hostedMeetings(id) : recordings.ownMeetings(id));
        List<ParticipantOption> participantOptions =
                participantOptions(hosted ? recordings.hostedParticipants(id) : recordings.ownParticipants(id));
        List<String> dates = queryDates((hosted ? recordings.hostedDates(id) : recordings.ownDates(id)).stream());
        return new Facets(meetingOptions, participantOptions, dates);
    }

    /** Rows (meetingId, title, folder date, latest start): one option per meeting, under its most recent title. */
    private static List<MeetingOption> meetingOptions(List<Object[]> rows) {
        Map<UUID, List<Object[]>> byMeeting = rows.stream().collect(Collectors.groupingBy(r -> (UUID) r[0]));
        return byMeeting.entrySet().stream()
                .map(e -> {
                    String title = (String) e.getValue().stream().max(Comparator.comparing(r -> (Instant) r[3])).orElseThrow()[1];
                    return new MeetingOption(e.getKey(), title, queryDates(e.getValue().stream().map(r -> (String) r[2])));
                })
                .sorted(Comparator.comparing(MeetingOption::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Rows (participantId, name, meetingId, folder date, host): one option per participation. */
    private static List<ParticipantOption> participantOptions(List<Object[]> rows) {
        Map<UUID, List<Object[]>> byParticipant = rows.stream().collect(Collectors.groupingBy(r -> (UUID) r[0]));
        return byParticipant.entrySet().stream()
                .map(e -> {
                    Object[] first = e.getValue().getFirst();
                    boolean host = e.getValue().stream().anyMatch(r -> Boolean.TRUE.equals(r[4]));
                    return new ParticipantOption(e.getKey(), (String) first[1], (UUID) first[2], host,
                            queryDates(e.getValue().stream().map(r -> (String) r[3])));
                })
                .sorted(Comparator.comparing(ParticipantOption::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Folder dates (DD-MM-YYYY) as distinct YYYY-MM-DD, newest first. */
    private static List<String> queryDates(Stream<String> folderDates) {
        return folderDates.distinct()
                .map(d -> LocalDate.parse(d, FOLDER_DATE))
                .sorted(Comparator.reverseOrder())
                .map(QUERY_DATE::format)
                .toList();
    }

    @Transactional(readOnly = true)
    public SpeechRecordingView get(UUID recordingId, CurrentUser user) {
        return SpeechRecordingView.of(authorized(recordingId, user));
    }

    /** A signed URL to play ({@code download=false}) or download the clip, valid for a few minutes. */
    @Transactional
    public AccessUrl accessUrl(UUID recordingId, boolean download, CurrentUser user) {
        SpeechRecording clip = authorized(recordingId, user);
        if (clip.getStatus() != SpeechRecording.Status.COMPLETED && clip.getStatus() != SpeechRecording.Status.INCOMPLETE) {
            throw new ApiException(ErrorCode.CONFLICT, "This recording has no file to play.");
        }
        // Its object may be gone before the row is updated (the S3 lifecycle rule runs on its own): say so now, rather
        // than hand out a link that ends in a storage error page.
        if (objectStore.enabled() && !objectStore.exists(clip.getStoragePath())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "This recording is no longer available.");
        }
        Instant expiresAt = clock.instant().plus(accessTtl);
        long expires = expiresAt.getEpochSecond();
        String url = publicUrl + CONTENT_PATH.replace("{id}", clip.getId().toString())
                + "?expires=" + expires + "&download=" + download + "&sig=" + sign(clip.getId(), expires, download);
        audit.record(user.userId(), null, AuditActions.SPEECH_RECORDING_ACCESSED, AuditActions.RESOURCE_SPEECH_RECORDING,
                clip.getId(), Map.of("download", download));
        return new AccessUrl(url, expiresAt);
    }

    /** The file behind a signed URL (the signature is the authorization, like a presigned storage URL). */
    @Transactional(readOnly = true)
    public ClipFile content(UUID recordingId, long expires, boolean download, String signature) {
        String expected = sign(recordingId, expires, download);
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            throw ApiException.forbidden("This link is not valid.");
        }
        if (clock.instant().getEpochSecond() > expires) {
            throw ApiException.forbidden("This link has expired.");
        }
        SpeechRecording clip = recordings.findById(recordingId).filter(r -> !r.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Recording"));
        if (objectStore.enabled()) {
            // The bucket is private: the browser is sent on to a presigned URL valid no longer than this link.
            Duration ttl = Duration.ofSeconds(Math.max(1, Math.min(accessTtl.toSeconds(),
                    expires - clock.instant().getEpochSecond())));
            String url = objectStore.presignGet(clip.getStoragePath(), clip.getFileName(), clip.getFileFormat(),
                    download, ttl);
            return new ClipFile(null, clip.getFileName(), clip.getFileFormat(), download, url);
        }
        return new ClipFile(storage.readable(clip.getStoragePath()), clip.getFileName(), clip.getFileFormat(), download,
                null);
    }

    /** Either a local {@code path} to stream or, with S3 storage, a presigned {@code redirectUrl}. */
    public record ClipFile(Path path, String fileName, String contentType, boolean download, String redirectUrl) {
    }

    /** The clip, if this user may see it (its meeting's host, or the participant it belongs to); 404 otherwise. */
    private SpeechRecording authorized(UUID recordingId, CurrentUser user) {
        return recordings.findById(recordingId)
                .filter(r -> !r.isDeleted())
                .filter(r -> user.userId().equals(r.getHostId()) || user.userId().equals(r.getParticipantUserId()))
                .orElseThrow(() -> ApiException.notFound("Recording"));
    }

    private static boolean isHosted(String view) {
        if (view == null || view.equals("hosted")) {
            return true;
        }
        if (view.equals("mine")) {
            return false;
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "view must be \"hosted\" or \"mine\".");
    }

    private static ZoneId zoneOf(Meeting meeting) {
        try {
            return meeting.getTimeZone() == null ? ZoneOffset.UTC : ZoneId.of(meeting.getTimeZone());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private String sign(UUID id, long expires, boolean download) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(signingKey, id + "\n" + expires + "\n" + download));
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stops reading after {@code limit} bytes, failing the chunk, so a client cannot stream an unbounded body. */
    static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                remaining -= n;
                if (remaining < 0) {
                    throw new ApiException(ErrorCode.FILE_TOO_LARGE, "Recording chunk too large.");
                }
            }
            return n;
        }
    }
}
