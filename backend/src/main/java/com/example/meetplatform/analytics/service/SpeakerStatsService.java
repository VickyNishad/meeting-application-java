package com.example.meetplatform.analytics.service;

import com.example.meetplatform.analytics.dto.SpeakerStatsDtos.Entry;
import com.example.meetplatform.analytics.dto.SpeakerStatsDtos.MeetingSpeakerStats;
import com.example.meetplatform.analytics.dto.SpeakerStatsDtos.SpeakerReport;
import com.example.meetplatform.analytics.dto.SpeakerStatsDtos.SpeakerStat;
import com.example.meetplatform.analytics.repository.SpeakerStatsRepository;
import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.scheduling.JobLock;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.security.RateLimiter;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.event.MeetingEvents;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.meeting.service.MeetingAccessService;
import com.example.meetplatform.organization.service.OrganizationService;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.participant.service.ParticipantAccessService;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.service.RoomSessionService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Speaking time per participant and session. The SFU tells every client who is speaking but does not report it to
 * the server, so one client reports it: the present moderator who joined first ("reporter"). Reports from anyone
 * else are refused, a batch number makes retries count once, and per-batch values are bounded. This trusts the
 * reporting moderator's client; it is not tamper-proof against a host who manipulates their own client.
 *
 * <p>Statistics are shown to the host, organization admins and system admins (a co-host: the live session only),
 * and expire with the recording retention period.
 */
@Service
public class SpeakerStatsService {

    /** Per participant and batch (a batch covers about 10 seconds). */
    static final long MAX_MS_PER_BATCH = 30_000;
    /** One uninterrupted speaking turn this long counts as someone having spoken (starts automatic recording). */
    static final long SPEECH_DETECTED_MS = 1000;
    static final long MAX_TURNS_PER_BATCH = 100;

    private final SpeakerStatsRepository stats;
    private final MeetingRepository meetings;
    private final MeetingParticipantRepository participants;
    private final ParticipantAccessService participantAccess;
    private final MeetingAccessService meetingAccess;
    private final OrganizationService organizations;
    private final RoomSessionService roomSessions;
    private final RateLimiter rateLimiter;
    private final JobLock jobLock;
    private final TransactionTemplate tx;
    private final AppProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public SpeakerStatsService(SpeakerStatsRepository stats, MeetingRepository meetings,
                               MeetingParticipantRepository participants, ParticipantAccessService participantAccess,
                               MeetingAccessService meetingAccess, OrganizationService organizations,
                               RoomSessionService roomSessions, RateLimiter rateLimiter, JobLock jobLock,
                               TransactionTemplate tx, AppProperties properties, Clock clock,
                               ApplicationEventPublisher events) {
        this.events = events;
        this.stats = stats;
        this.meetings = meetings;
        this.participants = participants;
        this.participantAccess = participantAccess;
        this.meetingAccess = meetingAccess;
        this.organizations = organizations;
        this.roomSessions = roomSessions;
        this.rateLimiter = rateLimiter;
        this.jobLock = jobLock;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return false when the batch was a retry of one already counted */
    @Transactional
    public boolean report(UUID meetingId, SpeakerReport report, CurrentUser user) {
        MeetingParticipant reporter = participantAccess.resolvePresent(meetingId, report.participantId(), null,
                Optional.of(user));
        if (!reporter.getRole().isModerator()) {
            throw ApiException.forbidden("Only the host or a co-host reports speaking time.");
        }
        MeetingRoom room = roomSessions.active(meetingId)
                .filter(r -> r.getId().equals(reporter.getRoomId()))
                .orElseThrow(() -> new ApiException(ErrorCode.MEETING_NOT_ACTIVE, "The meeting is not live."));
        List<MeetingParticipant> present = participants.findByRoomIdAndStateIn(room.getId(), MeetingParticipant.PRESENT);
        UUID elected = present.stream().filter(p -> p.getRole().isModerator())
                .min(Comparator.comparing((MeetingParticipant p) -> p.getJoinedAt() == null ? Instant.MAX : p.getJoinedAt())
                        .thenComparing(p -> p.getId().toString()))
                .map(MeetingParticipant::getId).orElse(null);
        if (!reporter.getId().equals(elected)) {
            throw new ApiException(ErrorCode.SPEAKER_REPORTER_MISMATCH,
                    "Another moderator is reporting speaking time for this session.");
        }
        rateLimiter.check("speaker-report", reporter.getId().toString(), 20, Duration.ofMinutes(1));
        Instant now = clock.instant();
        if (!stats.claimBatch(room.getId(), reporter.getId(), report.batchSeq(), now)) {
            return false;
        }
        // Only participants of this session are counted; anything else in the report is ignored.
        Set<UUID> inSession = participants.findByMeetingIdOrderByCreatedAtAsc(meetingId).stream()
                .filter(p -> room.getId().equals(p.getRoomId()))
                .map(MeetingParticipant::getId)
                .collect(Collectors.toSet());
        // Repeated entries for one participant are merged, and the batch limits apply to the merged total.
        Map<UUID, long[]> merged = new LinkedHashMap<>();
        for (Entry e : report.entries()) {
            long[] t = merged.computeIfAbsent(e.participantId(), k -> new long[3]);
            t[0] += e.speakingMs();
            t[1] += e.turns();
            t[2] = Math.max(t[2], e.longestTurnMs() == null ? 0 : e.longestTurnMs());
        }
        merged.forEach((participantId, t) -> {
            long ms = Math.min(t[0], MAX_MS_PER_BATCH);
            int turns = (int) Math.min(t[1], MAX_TURNS_PER_BATCH);
            if (inSession.contains(participantId) && (ms > 0 || turns > 0)) {
                stats.add(room.getId(), participantId, meetingId, ms, turns, now);
                // A backstop for automatic recording (browsers that cannot record speaking clips): one uninterrupted
                // turn of real length counts as speech. Not the batch total: several coughs or clicks, each briefly
                // taken for speech, must not add up to it.
                if (t[2] >= SPEECH_DETECTED_MS) {
                    events.publishEvent(new MeetingEvents.SpeechDetected(meetingId, participantId));
                }
            }
        });
        return true;
    }

    @Transactional(readOnly = true)
    public MeetingSpeakerStats forMeeting(UUID meetingId, CurrentUser user) {
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> ApiException.notFound("Meeting"));
        List<SpeakerStat> rows;
        if (canSeeAll(meeting, user)) {
            rows = stats.forMeeting(meetingId);
        } else {
            MeetingRoom live = roomSessions.active(meetingId)
                    .filter(room -> participants.findByRoomIdAndStateIn(room.getId(), MeetingParticipant.PRESENT)
                            .stream().anyMatch(p -> user.userId().equals(p.getUserId()) && p.getRole().isModerator()))
                    .orElse(null);
            if (live == null) {
                meetingAccess.requireView(meeting, user);
                throw ApiException.forbidden("Only the host can see speaking time.");
            }
            rows = stats.forRoom(live.getId());
        }
        return new MeetingSpeakerStats(meetingId, rows.stream().mapToLong(SpeakerStat::speakingMs).sum(), rows);
    }

    /** The host, an admin of the meeting's organization, or a system admin. */
    public boolean canSeeAll(Meeting meeting, CurrentUser user) {
        return meetingAccess.isHost(meeting, user.userId()) || user.systemAdmin()
                || organizations.isAdmin(meeting.getOrganizationId(), user.userId());
    }

    @Scheduled(cron = "${app.analytics.retention-cron:0 45 3 * * *}")
    public void enforceRetention() {
        jobLock.runExclusively("speaker-stats-retention", Duration.ofMinutes(10), () -> tx.executeWithoutResult(s -> {
            Instant now = clock.instant();
            stats.deleteOlderThan(now.minus(Duration.ofDays(properties.recording().defaultRetentionDays())));
            stats.deleteBatchesOlderThan(now.minus(Duration.ofDays(2)));
        }));
    }
}
