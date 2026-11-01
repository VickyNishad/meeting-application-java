package com.example.meetplatform.organization.repository;

import com.example.meetplatform.organization.entity.OrganizationMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {

    Optional<OrganizationMember> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    List<OrganizationMember> findByUserId(UUID userId);

    List<OrganizationMember> findByOrganizationIdOrderByJoinedAtAsc(UUID organizationId);

    long countByOrganizationIdAndRole(UUID organizationId, OrganizationMember.Role role);

    boolean existsByOrganizationIdAndUserId(UUID organizationId, UUID userId);
}
