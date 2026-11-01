package com.example.meetplatform.common.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Runs a scheduled job on one API instance at a time: a Redis lock ({@code SET NX PX}, released only by its owner)
 * keyed by job name. With {@code app.coordination.store=memory} (single instance) the job just runs. If Redis is
 * unreachable the job runs anyway: every job guarded by this lock is idempotent, so a rare double run is harmless,
 * while skipping would leave expired data in place.
 */
@Component
public class JobLock {

    private static final Logger log = LoggerFactory.getLogger(JobLock.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final boolean useRedis;

    public JobLock(StringRedisTemplate redis, @Value("${app.coordination.store:redis}") String store) {
        this.redis = redis;
        this.useRedis = "redis".equalsIgnoreCase(store);
    }

    /** @return false when another instance holds the lock and the job was skipped */
    public boolean runExclusively(String jobName, Duration maxRunTime, Runnable job) {
        if (!useRedis) {
            job.run();
            return true;
        }
        String key = "job-lock:" + jobName;
        String owner = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, maxRunTime));
        } catch (DataAccessException e) {
            log.warn("Job lock store unavailable, running {} without a lock: {}", jobName, e.getMessage());
            job.run();
            return true;
        }
        if (!acquired) {
            log.debug("Job {} is running on another instance", jobName);
            return false;
        }
        try {
            job.run();
            return true;
        } finally {
            try {
                redis.execute(RELEASE, List.of(key), owner);
            } catch (DataAccessException e) {
                log.warn("Could not release job lock {} (expires on its own): {}", jobName, e.getMessage());
            }
        }
    }
}
