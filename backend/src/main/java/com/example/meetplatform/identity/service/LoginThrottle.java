package com.example.meetplatform.identity.service;

import com.example.meetplatform.common.util.Tokens;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Failed-password lockout keyed by account <em>and</em> client IP: after {@code max} failures one client is
 * locked out of that account for the lockout duration, while the owner (and every other client) can still sign
 * in. Brute force spread over many addresses is additionally capped by the per-account rate limit in
 * {@link AuthService}. IP addresses are stored only as SHA-256.
 */
@Component
public class LoginThrottle {

    private final JdbcTemplate jdbc;

    public LoginThrottle(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isLocked(UUID userId, String clientIp, Instant now) {
        List<Timestamp> lockedUntil = jdbc.queryForList(
                "select locked_until from login_failures where user_id = ? and client_key = ?", Timestamp.class,
                userId, clientKey(clientIp));
        return !lockedUntil.isEmpty() && lockedUntil.get(0) != null && lockedUntil.get(0).toInstant().isAfter(now);
    }

    /** Counts a failure; the {@code max}-th consecutive one locks this client out until {@code lockUntil}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFailure(UUID userId, String clientIp, int max, Instant lockUntil, Instant now) {
        boolean lockNow = max <= 1;
        jdbc.update("""
                insert into login_failures (user_id, client_key, failed_attempts, locked_until, updated_at)
                values (?, ?, ?, ?, ?)
                on conflict (user_id, client_key) do update set
                    failed_attempts = case when login_failures.failed_attempts + 1 >= ? then 0
                                           else login_failures.failed_attempts + 1 end,
                    locked_until = case when login_failures.failed_attempts + 1 >= ? then cast(? as timestamptz)
                                        else login_failures.locked_until end,
                    updated_at = excluded.updated_at
                """, userId, clientKey(clientIp), lockNow ? 0 : 1, lockNow ? Timestamp.from(lockUntil) : null,
                Timestamp.from(now), max, max, Timestamp.from(lockUntil));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(UUID userId, String clientIp) {
        jdbc.update("delete from login_failures where user_id = ? and client_key = ?", userId, clientKey(clientIp));
    }

    /** A password reset proves mailbox control: lift every lockout of the account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clear(UUID userId) {
        jdbc.update("delete from login_failures where user_id = ?", userId);
    }

    private static String clientKey(String clientIp) {
        return Tokens.sha256(clientIp == null ? "unknown" : clientIp);
    }
}
