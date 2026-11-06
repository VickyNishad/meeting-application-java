package com.example.meetplatform.dashboard;

import com.example.meetplatform.analytics.dto.SpeakerStatsDtos.SpeakerStat;
import com.example.meetplatform.analytics.repository.SpeakerStatsRepository;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.recording.dto.RecordingDtos.RecordingView;
import com.example.meetplatform.recording.service.RecordingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The signed-in user's dashboard figures: their latest recordings (whatever the recording library lets them see),
 * and for meetings they host: recordings in progress, storage used, speaking clips kept, and who spoke how much in
 * recent meetings.
 */
@Service
public class DashboardService {

    private static final int RECENT_RECORDINGS = 5;
    private static final int HIGHLIGHT_MEETINGS = 3;
    private static final int TOP_SPEAKERS = 5;

    private final RecordingService recordings;
    private final SpeakerStatsRepository speakerStats;
    private final JdbcTemplate jdbc;

    public DashboardService(RecordingService recordings, SpeakerStatsRepository speakerStats, JdbcTemplate jdbc) {
        this.recordings = recordings;
        this.speakerStats = speakerStats;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary(CurrentUser user) {
        List<RecordingView> recent = recordings.library(user, 0, RECENT_RECORDINGS).items();
        Long inProgress = jdbc.queryForObject("""
                SELECT count(*) FROM recordings r JOIN meetings m ON m.id = r.meeting_id
                WHERE m.host_id = ? AND r.status IN ('STARTING', 'ACTIVE', 'STOPPING')
                """, Long.class, user.userId());
        Long storage = jdbc.queryForObject("""
                SELECT coalesce(sum(r.size_bytes), 0) FROM recordings r JOIN meetings m ON m.id = r.meeting_id
                WHERE m.host_id = ? AND r.status = 'AVAILABLE'
                """, Long.class, user.userId());
        List<SpeakingHighlight> highlights = jdbc.query("""
                SELECT s.meeting_id, m.title, max(s.last_spoke_at) AS last_spoke_at
                FROM speaker_stats s JOIN meetings m ON m.id = s.meeting_id
                WHERE m.host_id = ?
                GROUP BY s.meeting_id, m.title
                ORDER BY max(s.last_spoke_at) DESC NULLS LAST
                LIMIT ?
                """, (rs, i) -> highlight(rs.getObject("meeting_id", UUID.class), rs.getString("title"),
                instant(rs.getTimestamp("last_spoke_at"))), user.userId(), HIGHLIGHT_MEETINGS);
        // Speaking clips of the meetings the user hosts that still have a file: not those removed by retention.
        long[] clips = jdbc.queryForObject("""
                SELECT count(*), coalesce(sum(file_size), 0) FROM speech_recordings
                WHERE host_id = ? AND status IN ('COMPLETED', 'INCOMPLETE') AND deleted_at IS NULL
                """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, user.userId());
        return new DashboardSummary(recent, inProgress == null ? 0 : inProgress, storage == null ? 0 : storage,
                highlights, clips[0], clips[1]);
    }

    /** Speaking time summed over the meeting's sessions per participant row; top speakers with their share. */
    private SpeakingHighlight highlight(UUID meetingId, String title, Instant lastSpokeAt) {
        Map<UUID, long[]> perParticipant = new LinkedHashMap<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        for (SpeakerStat s : speakerStats.forMeeting(meetingId)) {
            perParticipant.computeIfAbsent(s.participantId(), k -> new long[1])[0] += s.speakingMs();
            names.putIfAbsent(s.participantId(), s.displayName());
        }
        long total = perParticipant.values().stream().mapToLong(v -> v[0]).sum();
        List<Speaker> top = perParticipant.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(TOP_SPEAKERS)
                .map(e -> new Speaker(names.get(e.getKey()), e.getValue()[0],
                        total == 0 ? 0 : Math.round(e.getValue()[0] * 1000.0 / total) / 10.0))
                .toList();
        return new SpeakingHighlight(meetingId, title, lastSpokeAt, total, top);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public record DashboardSummary(List<RecordingView> recentRecordings, long recordingsInProgress,
                                   long storageUsedBytes, List<SpeakingHighlight> speakingHighlights,
                                   long speechClipCount, long speechClipBytes) {
    }

    public record SpeakingHighlight(UUID meetingId, String title, Instant lastSpokeAt, long totalSpeakingMs,
                                    List<Speaker> topSpeakers) {
    }

    /** {@code sharePercent}: share of the meeting's total speaking time, one decimal. */
    public record Speaker(String displayName, long speakingMs, double sharePercent) {
    }
}
