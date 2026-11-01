package com.example.meetplatform.identity.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.identity.dto.UserDtos.ChangePasswordRequest;
import com.example.meetplatform.identity.dto.UserDtos.SessionView;
import com.example.meetplatform.identity.dto.UserDtos.UpdateProfileRequest;
import com.example.meetplatform.identity.dto.UserDtos.UserProfile;
import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.entity.UserCredential;
import com.example.meetplatform.identity.event.IdentityEvents;
import com.example.meetplatform.identity.mapper.UserMapper;
import com.example.meetplatform.identity.repository.UserCredentialRepository;
import com.example.meetplatform.identity.repository.UserRepository;
import com.example.meetplatform.identity.repository.UserSessionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Profile management and the read API other modules use to look users up (the "user directory"). */
@Service
public class UserService {

    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final UserSessionRepository sessionRepository;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public UserService(UserRepository users, UserCredentialRepository credentials, UserSessionRepository sessionRepository,
                       SessionService sessions, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                       AuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.users = users;
        this.credentials = credentials;
        this.sessionRepository = sessionRepository;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserProfile profile(UUID userId) {
        return UserMapper.toProfile(require(userId));
    }

    @Transactional
    public UserProfile updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = require(userId);
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.timeZone() != null) {
            user.setTimeZone(request.timeZone());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl().isBlank() ? null : request.avatarUrl());
        }
        return UserMapper.toProfile(user);
    }

    @Transactional
    public void changePassword(CurrentUser current, ChangePasswordRequest request) {
        User user = require(current.userId());
        UserCredential cred = credentials.findById(user.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "This account signs in with an external provider and has no password."));
        if (!passwordEncoder.matches(request.currentPassword(), cred.getPasswordHash())) {
            // Not a 401: the session is fine, so clients must not treat this as an expired login.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The current password is incorrect.");
        }
        passwordPolicy.validate(request.newPassword(), user.getEmail(), user.getDisplayName());
        cred.changePassword(passwordEncoder.encode(request.newPassword()), clock.instant());
        sessions.revokeAllExcept(user.getId(), current.sessionId(), "PASSWORD_CHANGED");
        audit.record(AuditActions.USER_PASSWORD_CHANGED, AuditActions.RESOURCE_USER, user.getId(), Map.of());
        events.publishEvent(new IdentityEvents.PasswordChanged(user.getId(), user.getEmail(), user.getDisplayName()));
    }

    @Transactional(readOnly = true)
    public List<SessionView> sessions(CurrentUser current) {
        return sessionRepository.findActiveByUser(current.userId(), clock.instant()).stream()
                .map(s -> new SessionView(s.getId(), s.getCreatedAt(), s.getLastUsedAt(), s.getUserAgent(),
                        s.getIpAddress(), s.getId().equals(current.sessionId())))
                .toList();
    }

    @Transactional
    public void revokeSession(CurrentUser current, UUID sessionId) {
        sessions.revoke(current.userId(), sessionId, "USER_REVOKED");
        audit.record(AuditActions.USER_SESSION_REVOKED, AuditActions.RESOURCE_USER, current.userId(),
                Map.of("sessionId", sessionId));
    }

    // ------------------------------------------------------------------ directory API for other modules

    @Transactional(readOnly = true)
    public User require(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return users.findByEmailIgnoreCase(email.trim());
    }

    @Transactional(readOnly = true)
    public UserSummary summary(UUID userId) {
        return UserMapper.toSummary(require(userId));
    }

    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> summaries(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findByIdIn(userIds).stream()
                .collect(Collectors.toMap(User::getId, UserMapper::toSummary, (a, b) -> a));
    }

    @Transactional(readOnly = true)
    public Map<UUID, User> usersById(Collection<UUID> userIds) {
        return users.findByIdIn(userIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
