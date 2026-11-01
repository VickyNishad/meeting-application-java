package com.example.meetplatform.common;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.security.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Budget counting of {@link RateLimiter#consume} against an in-memory stand-in for Redis INCRBY. */
class RateLimiterTest {

    private final Map<String, Long> counters = new HashMap<>();

    private RateLimiter limiter(boolean enabled, StringRedisTemplate redis) {
        AppProperties props = mock(AppProperties.class);
        when(props.rateLimit()).thenReturn(new AppProperties.RateLimit(enabled));
        return new RateLimiter(redis, props, Clock.fixed(Instant.parse("2026-10-28T10:15:00Z"), ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate redis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString(), anyLong())).thenAnswer(inv ->
                counters.merge(inv.getArgument(0), inv.getArgument(1), Long::sum));
        return redis;
    }

    @Test
    void permitsAccumulateUntilTheBudgetIsExceeded() {
        RateLimiter limiter = limiter(true, redis());
        limiter.consume("meeting-invitations", "host-1", 300, 500, Duration.ofHours(1));
        limiter.consume("meeting-invitations", "host-1", 200, 500, Duration.ofHours(1));
        assertThatThrownBy(() -> limiter.consume("meeting-invitations", "host-1", 1, 500, Duration.ofHours(1)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(e.retryAfterSeconds()).isEqualTo(45 * 60L);
                });
        // Budgets are per key.
        assertThatCode(() -> limiter.consume("meeting-invitations", "host-2", 500, 500, Duration.ofHours(1)))
                .doesNotThrowAnyException();
        // check() is a single permit.
        limiter.check("meeting-reschedule", "host-1", 1, Duration.ofHours(1));
        assertThatThrownBy(() -> limiter.check("meeting-reschedule", "host-1", 1, Duration.ofHours(1)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void disabledLimiterNeverTouchesRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        limiter(false, redis).consume("meeting-invitations", "host-1", 10_000, 1, Duration.ofHours(1));
        verifyNoInteractions(redis);
    }
}
