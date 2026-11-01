package com.example.meetplatform.identity.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.common.security.JwtService;
import com.example.meetplatform.common.security.SessionStatusChecker;
import com.example.meetplatform.common.util.Tokens;
import com.example.meetplatform.identity.dto.AuthDtos.ClientType;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.entity.UserSession;
import com.example.meetplatform.identity.repository.UserRepository;
import com.example.meetplatform.identity.repository.UserSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refresh-token sessions: creation, rotation with reuse detection, and revocation. Also answers the
 * "is this session still active" question for access-token validation, with a short local cache so a
 * revocation takes effect within {@link #STATUS_CACHE_TTL} on every instance.
 */
@Service
public class SessionService implements SessionStatusChecker {

    static final Duration STATUS_CACHE_TTL = Duration.ofSeconds(10);
    private static final Duration ROTATION_RACE_GRACE = Duration.ofSeconds(30);
    private static final int MAX_CACHE_ENTRIES = 50_000;

    private final UserSessionRepository sessions;
    private final UserRepository users;
    private final JwtService jwtService;
    private final AuditService audit;
    private final AppProperties.Security props;
    private final Clock clock;
    private final Map<UUID, CachedStatus> statusCache = new ConcurrentHashMap<>();

    public SessionService(UserSessionRepository sessions, UserRepository users, JwtService jwtService, AuditService audit,
                          AppProperties properties, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.jwtService = jwtService;
        this.audit = audit;
        this.props = properties.security();
        this.clock = clock;
    }

    @Transactional
    public IssuedSession create(User user, ClientType clientType, ClientInfo client) {
        Instant now = clock.instant();
        String refreshToken = Tokens.randomSecret(32);
        UserSession session = sessions.save(new UserSession(user.getId(), Tokens.sha256(refreshToken), clientType.name(),
                client.userAgent(), client.ipAddress(), now, now.plus(props.refreshTokenTtl())));
        return issue(user, session, refreshToken, clientType);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public IssuedSession rotate(String presentedRefreshToken, ClientInfo client) {
        Instant now = clock.instant();
        String hash = Tokens.sha256(presentedRefreshToken);
        Optional<UserSession> current = sessions.findByRefreshTokenHashForUpdate(hash);
        if (current.isEmpty()) {
            sessions.findByPreviousTokenHashForUpdate(hash).ifPresent(session -> {
                // A rotated token was presented again. Within a short window this is two tabs racing; outside it,
                // the token was most likely stolen, so the whole session is revoked.
                if (session.getLastUsedAt().plus(ROTATION_RACE_GRACE).isBefore(now) && session.isUsable(now)) {
                    session.revoke(now, "REFRESH_REUSE_DETECTED");
                    statusCache.remove(session.getId());
                    audit.record(session.getUserId(), null, AuditActions.USER_REFRESH_REUSE_DETECTED,
                            AuditActions.RESOURCE_USER, session.getUserId(), Map.of("sessionId", session.getId()));
                }
            });
            throw new ApiException(ErrorCode.TOKEN_INVALID, "The refresh token is invalid.");
        }
        UserSession session = current.get();
        if (!session.isUsable(now)) {
            throw new ApiException(ErrorCode.TOKEN_INVALID, "The session has expired or was revoked.");
        }
        User user = users.findById(session.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID, "The refresh token is invalid."));
        if (!user.isActive()) {
            session.revoke(now, "ACCOUNT_DISABLED");
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED, "This account is disabled.");
        }
        String newToken = Tokens.randomSecret(32);
        session.rotate(Tokens.sha256(newToken), now, client.ipAddress(), client.userAgent());
        return issue(user, session, newToken, ClientType.valueOf(session.getClientType()));
    }

    /** Revokes the session the refresh token belongs to. Unknown tokens are ignored (logout is idempotent). */
    @Transactional
    public Optional<UUID> revokeByRefreshToken(String refreshToken, String reason) {
        return sessions.findByRefreshTokenHashForUpdate(Tokens.sha256(refreshToken)).map(session -> {
            session.revoke(clock.instant(), reason);
            statusCache.remove(session.getId());
            return session.getUserId();
        });
    }

    @Transactional
    public void revoke(UUID userId, UUID sessionId, String reason) {
        UserSession session = sessions.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Session"));
        session.revoke(clock.instant(), reason);
        statusCache.remove(sessionId);
    }

    @Transactional
    public void revokeAll(UUID userId, String reason) {
        sessions.findUnrevokedIds(userId).forEach(statusCache::remove);
        sessions.revokeAllForUser(userId, clock.instant(), reason);
    }

    @Transactional
    public void revokeAllExcept(UUID userId, UUID keepSessionId, String reason) {
        sessions.findUnrevokedIds(userId).stream().filter(id -> !id.equals(keepSessionId)).forEach(statusCache::remove);
        sessions.revokeAllForUserExcept(userId, keepSessionId, clock.instant(), reason);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID sessionId) {
        long nowMillis = clock.millis();
        CachedStatus cached = statusCache.get(sessionId);
        if (cached != null && cached.expiresAtMillis > nowMillis) {
            return cached.active;
        }
        boolean active = sessions.findById(sessionId).map(s -> s.isUsable(clock.instant())).orElse(false);
        if (statusCache.size() > MAX_CACHE_ENTRIES) {
            statusCache.clear();
        }
        statusCache.put(sessionId, new CachedStatus(active, nowMillis + STATUS_CACHE_TTL.toMillis()));
        return active;
    }

    private IssuedSession issue(User user, UserSession session, String refreshToken, ClientType clientType) {
        JwtService.IssuedAccessToken access = jwtService.issue(user.getId(), user.getEmail(), user.getSystemRole().name(),
                session.getId());
        return new IssuedSession(user, session.getId(), access.value(), access.expiresInSeconds(), refreshToken, clientType);
    }

    public record IssuedSession(User user, UUID sessionId, String accessToken, long expiresIn, String refreshToken,
                                ClientType clientType) {
    }

    private record CachedStatus(boolean active, long expiresAtMillis) {
    }
}
