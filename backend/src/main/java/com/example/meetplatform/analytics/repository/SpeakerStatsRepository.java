package com.example.meetplatform.analytics.repository;

import com.example.meetplatform.analytics.dto.SpeakerStatsDtos.SpeakerStat;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Speaking-time counters (composite key, additive upserts): plain SQL rather than JPA. */
@Repository
public class SpeakerStatsRepository {

    private static final RowMapper<SpeakerStat> STAT = (rs, i) -> new SpeakerStat(
            rs.getObject("room_id", UUID.class), rs.getObject("participant_id", UUID.class),
            rs.getString("display_name"), MeetingRole.valueOf(rs.getString("role")),
            rs.getLong("speaking_ms"), rs.getInt("turns"), instant(rs.getTimestamp("first_spoke_at")),
            instant(rs.getTimestamp("last_spoke_at")));

    private static final String SELECT = """
            SELECT s.room_id, s.participant_id, p.display_name, p.role, s.speaking_ms, s.turns,
                   s.first_spoke_at, s.last_spoke_at
            FROM speaker_stats s JOIN meeting_participants p ON p.id = s.participant_id
            """;

    private final JdbcTemplate jdbc;

    public SpeakerStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when this (session, reporter, batch) was already received */
    public boolean claimBatch(UUID roomId, UUID reporterId, long batchSeq, Instant now) {
        return jdbc.update("""
                INSERT INTO speaker_stat_batches (room_id, reporter_id, batch_seq, received_at) VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, roomId, reporterId, batchSeq, Timestamp.from(now)) == 1;
    }

    public void add(UUID roomId, UUID participantId, UUID meetingId, long speakingMs, int turns, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO speaker_stats (room_id, participant_id, meeting_id, speaking_ms, turns, first_spoke_at,
                                           last_spoke_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (room_id, participant_id) DO UPDATE SET
                    speaking_ms = speaker_stats.speaking_ms + EXCLUDED.speaking_ms,
                    turns = speaker_stats.turns + EXCLUDED.turns,
                    first_spoke_at = COALESCE(speaker_stats.first_spoke_at, EXCLUDED.first_spoke_at),
                    last_spoke_at = EXCLUDED.last_spoke_at,
                    updated_at = EXCLUDED.updated_at
                """, roomId, participantId, meetingId, speakingMs, turns, ts, ts, ts);
    }

    public List<SpeakerStat> forMeeting(UUID meetingId) {
        return jdbc.query(SELECT + " WHERE s.meeting_id = ? ORDER BY s.speaking_ms DESC", STAT, meetingId);
    }

    public List<SpeakerStat> forRoom(UUID roomId) {
        return jdbc.query(SELECT + " WHERE s.room_id = ? ORDER BY s.speaking_ms DESC", STAT, roomId);
    }

    /** Speaking time is personal data: it expires with the recording retention period. */
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM speaker_stats WHERE updated_at < ?", Timestamp.from(cutoff));
    }

    public int deleteBatchesOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM speaker_stat_batches WHERE received_at < ?", Timestamp.from(cutoff));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
