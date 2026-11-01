package com.example.meetplatform.identity.repository;

import com.example.meetplatform.identity.entity.UserSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSession s where s.refreshTokenHash = :hash")
    Optional<UserSession> findByRefreshTokenHashForUpdate(String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSession s where s.previousTokenHash = :hash")
    Optional<UserSession> findByPreviousTokenHashForUpdate(String hash);

    @Query("select s from UserSession s where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now order by s.lastUsedAt desc")
    List<UserSession> findActiveByUser(UUID userId, Instant now);

    @Query("select s.id from UserSession s where s.userId = :userId and s.revokedAt is null")
    List<UUID> findUnrevokedIds(UUID userId);

    @Modifying
    @Query("update UserSession s set s.revokedAt = :now, s.revokeReason = :reason where s.userId = :userId and s.revokedAt is null")
    int revokeAllForUser(UUID userId, Instant now, String reason);

    @Modifying
    @Query("""
            update UserSession s set s.revokedAt = :now, s.revokeReason = :reason
            where s.userId = :userId and s.revokedAt is null and s.id <> :keepSessionId
            """)
    int revokeAllForUserExcept(UUID userId, UUID keepSessionId, Instant now, String reason);
}
