package com.example.meetplatform.media.service;

import com.example.meetplatform.common.persistence.AfterCommit;
import com.example.meetplatform.room.event.RoomEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-room sequence numbers for server signals, so clients can tell when they missed one and must reload the room
 * state ({@code GET /meetings/{id}/room-state}). Two streams: {@code all} (signals everyone receives) and
 * {@code stage} (signals only the host and co-hosts receive). Signals to single participants carry no number.
 *
 * <p>Every number belongs to an <em>epoch</em>, a random id created with the room's counters. Counters that are
 * lost (Redis restarted, flushed or expired, or the local counters of a finished session) come back with a new
 * epoch, so clients see the change and reload instead of mistaking new low numbers for old signals.
 *
 * <p>Backed by Redis (one hash per room) so every API instance numbers a room's signals from one counter. With
 * {@code app.coordination.store=memory} (single instance, local development) the counters are local. While Redis
 * is unreachable signals are sent unnumbered: clients apply them as they come.
 */
@Component
public class RoomSequence {

    public static final String STREAM_ALL = "all";
    public static final String STREAM_STAGE = "stage";

    /** A signal number; {@code epoch} identifies the counters it came from. */
    public record Number(String epoch, long seq) {
    }

    private static final Logger log = LoggerFactory.getLogger(RoomSequence.class);
    private static final Duration KEY_TTL = Duration.ofDays(2);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> NEXT = new DefaultRedisScript<>("""
            redis.call('HSETNX', KEYS[1], 'epoch', ARGV[1])
            local n = redis.call('HINCRBY', KEYS[1], ARGV[2], 1)
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return {redis.call('HGET', KEYS[1], 'epoch'), tostring(n)}
            """, List.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CURRENT = new DefaultRedisScript<>("""
            redis.call('HSETNX', KEYS[1], 'epoch', ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return {redis.call('HGET', KEYS[1], 'epoch'), tostring(redis.call('HGET', KEYS[1], ARGV[2]) or 0)}
            """, List.class);

    private final StringRedisTemplate redis;
    private final boolean useRedis;
    private final ConcurrentMap<String, Counters> local = new ConcurrentHashMap<>();

    public RoomSequence(StringRedisTemplate redis, @Value("${app.coordination.store:redis}") String store) {
        this.redis = redis;
        this.useRedis = "redis".equalsIgnoreCase(store);
    }

    /** The next number, or empty when no shared counter is reachable (the signal is then sent unnumbered). */
    public Optional<Number> next(String roomName, String stream) {
        if (!useRedis) {
            Counters c = local.computeIfAbsent(roomName, k -> new Counters());
            return Optional.of(new Number(c.epoch, c.of(stream).incrementAndGet()));
        }
        try {
            return Optional.of(toNumber(redis.execute(NEXT, List.of(key(roomName)), newEpoch(), stream,
                    String.valueOf(KEY_TTL.toSeconds()))));
        } catch (DataAccessException | IllegalStateException e) {
            log.warn("Room sequence store unavailable, sending the signal unnumbered: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The latest number given out, in the room's current epoch (created if there is none yet). */
    public Number current(String roomName, String stream) {
        if (!useRedis) {
            Counters c = local.computeIfAbsent(roomName, k -> new Counters());
            return new Number(c.epoch, c.of(stream).get());
        }
        try {
            return toNumber(redis.execute(CURRENT, List.of(key(roomName)), newEpoch(), stream,
                    String.valueOf(KEY_TTL.toSeconds())));
        } catch (DataAccessException | IllegalStateException e) {
            log.warn("Room sequence store unavailable: {}", e.getMessage());
            // No epoch: the client accepts whatever numbered signals arrive and reloads once the store is back.
            return new Number(null, 0L);
        }
    }

    /**
     * Local counters only live as long as their session (Redis hashes expire on their own). Removed after commit,
     * so the session's last signals (meeting ended, recording stopped) are still numbered in its epoch.
     */
    @EventListener
    public void onSessionEnded(RoomEvents.SessionEnded event) {
        if (event.roomName() != null) {
            AfterCommit.run(() -> local.remove(event.roomName()));
        }
    }

    @SuppressWarnings("rawtypes")
    private static Number toNumber(List result) {
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("unexpected room sequence result");
        }
        return new Number(String.valueOf(result.get(0)), Long.parseLong(String.valueOf(result.get(1))));
    }

    private static String newEpoch() {
        return UUID.randomUUID().toString();
    }

    private static String key(String roomName) {
        return "room-seq:" + roomName;
    }

    private static final class Counters {
        final String epoch = newEpoch();
        final AtomicLong all = new AtomicLong();
        final AtomicLong stage = new AtomicLong();

        AtomicLong of(String stream) {
            return STREAM_STAGE.equals(stream) ? stage : all;
        }
    }
}
