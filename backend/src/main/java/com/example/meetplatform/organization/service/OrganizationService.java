package com.example.meetplatform.organization.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.identity.service.UserService;
import com.example.meetplatform.organization.dto.OrganizationDtos.AddMemberRequest;
import com.example.meetplatform.organization.dto.OrganizationDtos.CreateOrganizationRequest;
import com.example.meetplatform.organization.dto.OrganizationDtos.MemberView;
import com.example.meetplatform.organization.dto.OrganizationDtos.OrganizationView;
import com.example.meetplatform.organization.dto.OrganizationDtos.UpdateOrganizationRequest;
import com.example.meetplatform.organization.entity.Organization;
import com.example.meetplatform.organization.entity.OrganizationMember;
import com.example.meetplatform.organization.entity.OrganizationMember.Role;
import com.example.meetplatform.organization.repository.OrganizationMemberRepository;
import com.example.meetplatform.organization.repository.OrganizationRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final UserService userService;
    private final AuditService audit;
    private final Clock clock;

    public OrganizationService(OrganizationRepository organizations, OrganizationMemberRepository members,
                               UserService userService, AuditService audit, Clock clock) {
        this.organizations = organizations;
        this.members = members;
        this.userService = userService;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public OrganizationView create(CurrentUser user, CreateOrganizationRequest request) {
        if (organizations.existsBySlug(request.slug())) {
            throw new ApiException(ErrorCode.SLUG_TAKEN, "This organization slug is already in use.");
        }
        Organization org = organizations.save(new Organization(request.name().trim(), request.slug()));
        members.save(new OrganizationMember(org.getId(), user.userId(), Role.ORGANIZATION_ADMIN, clock.instant()));
        audit.record(AuditActions.ORG_CREATED, AuditActions.RESOURCE_ORGANIZATION, org.getId(), Map.of("slug", org.getSlug()));
        return view(org, Role.ORGANIZATION_ADMIN);
    }

    @Transactional(readOnly = true)
    public List<OrganizationView> mine(CurrentUser user) {
        return members.findByUserId(user.userId()).stream()
                .map(m -> organizations.findById(m.getOrganizationId()).map(o -> view(o, m.getRole())))
                .flatMap(Optional::stream)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationView get(CurrentUser user, UUID orgId) {
        OrganizationMember membership = requireMember(user, orgId);
        return view(require(orgId), membership.getRole());
    }

    @Transactional
    public OrganizationView update(CurrentUser user, UUID orgId, UpdateOrganizationRequest request) {
        requireAdmin(user, orgId);
        Organization org = require(orgId);
        if (request.version() != null && !request.version().equals(org.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Organization.class, orgId);
        }
        if (request.name() != null) {
            org.setName(request.name().trim());
        }
        if (request.defaultRecordingRetentionDays() != null) {
            org.setDefaultRecordingRetentionDays(request.defaultRecordingRetentionDays());
        }
        if (request.allowGuests() != null) {
            org.setAllowGuests(request.allowGuests());
        }
        organizations.flush();
        audit.record(AuditActions.ORG_UPDATED, AuditActions.RESOURCE_ORGANIZATION, orgId, Map.of());
        return view(org, Role.ORGANIZATION_ADMIN);
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(CurrentUser user, UUID orgId) {
        requireMember(user, orgId);
        List<OrganizationMember> list = members.findByOrganizationIdOrderByJoinedAtAsc(orgId);
        var summaries = userService.summaries(list.stream().map(OrganizationMember::getUserId).toList());
        return list.stream().map(m -> new MemberView(summaries.get(m.getUserId()), m.getRole(), m.getJoinedAt())).toList();
    }

    @Transactional
    public void addMember(CurrentUser user, UUID orgId, AddMemberRequest request) {
        requireAdmin(user, orgId);
        var target = userService.findByEmail(request.email())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No account exists for this e-mail. Ask the person to register first."));
        if (members.existsByOrganizationIdAndUserId(orgId, target.getId())) {
            throw new ApiException(ErrorCode.CONFLICT, "This user is already a member.");
        }
        members.save(new OrganizationMember(orgId, target.getId(), request.role(), clock.instant()));
        audit.record(AuditActions.ORG_MEMBER_ADDED, AuditActions.RESOURCE_ORGANIZATION, orgId,
                Map.of("userId", target.getId(), "role", request.role()));
    }

    @Transactional
    public void changeRole(CurrentUser user, UUID orgId, UUID memberUserId, Role role) {
        lockOrganization(orgId);
        requireAdmin(user, orgId);
        OrganizationMember member = members.findByOrganizationIdAndUserId(orgId, memberUserId)
                .orElseThrow(() -> ApiException.notFound("Member"));
        if (member.getRole() == Role.ORGANIZATION_ADMIN && role != Role.ORGANIZATION_ADMIN) {
            ensureNotLastAdmin(orgId);
        }
        member.setRole(role);
        audit.record(AuditActions.ORG_MEMBER_ROLE_CHANGED, AuditActions.RESOURCE_ORGANIZATION, orgId,
                Map.of("userId", memberUserId, "role", role));
    }

    @Transactional
    public void removeMember(CurrentUser user, UUID orgId, UUID memberUserId) {
        lockOrganization(orgId);
        if (!user.userId().equals(memberUserId)) {
            requireAdmin(user, orgId);
        }
        OrganizationMember member = members.findByOrganizationIdAndUserId(orgId, memberUserId)
                .orElseThrow(() -> ApiException.notFound("Member"));
        if (member.getRole() == Role.ORGANIZATION_ADMIN) {
            ensureNotLastAdmin(orgId);
        }
        members.delete(member);
        audit.record(AuditActions.ORG_MEMBER_REMOVED, AuditActions.RESOURCE_ORGANIZATION, orgId,
                Map.of("userId", memberUserId));
    }

    // ------------------------------------------------------------------ queries for other modules

    @Transactional(readOnly = true)
    public boolean isMember(UUID orgId, UUID userId) {
        return orgId != null && userId != null && members.existsByOrganizationIdAndUserId(orgId, userId);
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(UUID orgId, UUID userId) {
        return orgId != null && userId != null && members.findByOrganizationIdAndUserId(orgId, userId)
                .map(m -> m.getRole() == Role.ORGANIZATION_ADMIN).orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<Organization> find(UUID orgId) {
        return orgId == null ? Optional.empty() : organizations.findById(orgId);
    }

    /** Members are checked after the lock, so a 404 for non-members is still returned by the caller's checks. */
    private void lockOrganization(UUID orgId) {
        organizations.findByIdForUpdate(orgId).orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private void ensureNotLastAdmin(UUID orgId) {
        if (members.countByOrganizationIdAndRole(orgId, Role.ORGANIZATION_ADMIN) <= 1) {
            throw new ApiException(ErrorCode.CONFLICT, "An organization must keep at least one administrator.");
        }
    }

    private OrganizationMember requireMember(CurrentUser user, UUID orgId) {
        return members.findByOrganizationIdAndUserId(orgId, user.userId())
                .orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private void requireAdmin(CurrentUser user, UUID orgId) {
        if (requireMember(user, orgId).getRole() != Role.ORGANIZATION_ADMIN && !user.systemAdmin()) {
            throw ApiException.forbidden("Organization administrator role required.");
        }
    }

    private Organization require(UUID orgId) {
        return organizations.findById(orgId).orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private static OrganizationView view(Organization org, Role myRole) {
        return new OrganizationView(org.getId(), org.getName(), org.getSlug(), org.getDefaultRecordingRetentionDays(),
                org.isAllowGuests(), org.getCreatedAt(), org.getVersion() == null ? 0 : org.getVersion(),
                myRole == null ? null : myRole.name());
    }
}
