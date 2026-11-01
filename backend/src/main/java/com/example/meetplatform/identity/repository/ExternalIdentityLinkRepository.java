package com.example.meetplatform.identity.repository;

import com.example.meetplatform.identity.entity.ExternalIdentityLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExternalIdentityLinkRepository extends JpaRepository<ExternalIdentityLink, UUID> {

    Optional<ExternalIdentityLink> findByProviderAndSubject(String provider, String subject);

    long deleteByUserId(UUID userId);
}
