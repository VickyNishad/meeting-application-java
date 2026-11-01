package com.example.meetplatform.common.security;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.util.Tokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Fixed-window rate limiter backed by Redis so limits hold across all stateless API instances.
 * Keys are hashed so e-mail addresses and IPs never appear in Redis. If Redis is unreachable the limiter
 * fails open (and logs) rather than taking the API down.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redis, AppProperties properties, Clock clock) {
        this.redis = redis;
        this.enabled = properties.rateLimit().enabled();
        this.clock = clock;
    }

    public void check(String bucket, String key, int limit, Duration window) {
        consume(bucket, key, 1, limit, window);
    }

    /**
     * Counts {@code permits} units (e.g. e-mails about to be sent) against the window's budget of {@code limit}
     * units. A request that would exceed the budget is rejected with 429; its permits stay counted.
     */
    public void consume(String bucket, String key, long permits, int limit, Duration window) {
        if (!enabled || key == null || permits <= 0) {
            return;
        }
        long windowSeconds = window.toSeconds();
        long nowSeconds = clock.instant().getEpochSecond();
        long windowIndex = nowSeconds / windowSeconds;
        String redisKey = "rl:" + bucket + ":" + Tokens.sha256(key) + ":" + windowIndex;
        try {
            Long count = redis.opsForValue().increment(redisKey, permits);
            if (count != null && count == permits) {
                redis.expire(redisKey, window);
            }
            if (count != null && count > limit) {
                long retryAfter = (windowIndex + 1) * windowSeconds - nowSeconds;
                throw ApiException.rateLimited(Math.max(1, retryAfter));
            }
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable, allowing request (bucket={}): {}", bucket, e.getMessage());
        }
    }
}
