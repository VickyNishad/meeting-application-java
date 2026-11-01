package com.example.meetplatform.identity.repository;

import com.example.meetplatform.identity.entity.VerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {

    Optional<VerificationToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            update VerificationToken t set t.usedAt = :now
            where t.userId = :userId and t.purpose = :purpose and t.usedAt is null
            """)
    int invalidateOutstanding(UUID userId, VerificationToken.Purpose purpose, Instant now);
}
