package com.example.meetplatform.identity.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.common.security.RateLimiter;
import com.example.meetplatform.common.util.Tokens;
import com.example.meetplatform.identity.dto.AuthDtos.ClientType;
import com.example.meetplatform.identity.dto.AuthDtos.LoginRequest;
import com.example.meetplatform.identity.dto.AuthDtos.RegisterRequest;
import com.example.meetplatform.identity.dto.AuthDtos.RegisterResponse;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.entity.UserCredential;
import com.example.meetplatform.identity.entity.VerificationToken;
import com.example.meetplatform.identity.event.IdentityEvents;
import com.example.meetplatform.identity.repository.ExternalIdentityLinkRepository;
import com.example.meetplatform.identity.repository.UserCredentialRepository;
import com.example.meetplatform.identity.repository.UserRepository;
import com.example.meetplatform.identity.repository.VerificationTokenRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final VerificationTokenRepository verificationTokens;
    private final ExternalIdentityLinkRepository externalLinks;
    private final LoginThrottle loginThrottle;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final AppProperties.Auth props;
    private final Clock clock;
    /** Hash compared against when the e-mail is unknown, so response time does not reveal account existence. */
    private final String dummyHash;

    public AuthService(UserRepository users, UserCredentialRepository credentials,
                       VerificationTokenRepository verificationTokens, ExternalIdentityLinkRepository externalLinks,
                       LoginThrottle loginThrottle, SessionService sessions,
                       PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, RateLimiter rateLimiter,
                       AuditService audit, ApplicationEventPublisher events, AppProperties properties, Clock clock) {
        this.users = users;
        this.credentials = credentials;
        this.verificationTokens = verificationTokens;
        this.externalLinks = externalLinks;
        this.loginThrottle = loginThrottle;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.events = events;
        this.props = properties.auth();
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(Tokens.randomSecret(16));
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request, ClientInfo client) {
        rateLimiter.check("register-ip", client.ipAddress(), 10, Duration.ofHours(1));
        String email = normalize(request.email());
        passwordPolicy.validate(request.password(), email, request.displayName());
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this e-mail already exists.");
        }
        Instant now = clock.instant();
        User user = new User(email, request.displayName().trim(), request.timeZone());
        user.setEmailVerified(!props.requireEmailVerification());
        users.save(user);
        credentials.save(new UserCredential(user.getId(), passwordEncoder.encode(request.password()), now));
        audit.record(user.getId(), null, AuditActions.USER_REGISTERED, AuditActions.RESOURCE_USER, user.getId(), Map.of());
        if (props.requireEmailVerification()) {
            sendVerification(user, now);
        }
        return new RegisterResponse(user.getId(), props.requireEmailVerification());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public SessionService.IssuedSession login(LoginRequest request, ClientInfo client) {
        String email = normalize(request.email());
        rateLimiter.check("login-ip", client.ipAddress(), 30, WINDOW);
        // Per account and client: a stranger hitting the limit must not lock the owner out. The per-account cap is
        // much higher and only throttles brute force spread over many addresses.
        rateLimiter.check("login-email-ip", email + "|" + client.ipAddress(), 10, WINDOW);
        rateLimiter.check("login-email", email, 100, WINDOW);
        Instant now = clock.instant();

        Optional<User> maybeUser = users.findByEmailIgnoreCase(email);
        Optional<UserCredential> credential = maybeUser.flatMap(u -> credentials.findById(u.getId()));
        if (maybeUser.isEmpty() || credential.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials();
        }
        User user = maybeUser.get();
        UserCredential cred = credential.get();
        // Lockout is per account and client IP (see LoginThrottle), so failures from elsewhere cannot lock the owner out.
        if (loginThrottle.isLocked(user.getId(), client.ipAddress(), now)) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS,
                    "Too many failed attempts. Sign-in from this device is temporarily locked; try again later.");
        }
        if (!passwordEncoder.matches(request.password(), cred.getPasswordHash())) {
            loginThrottle.recordFailure(user.getId(), client.ipAddress(), props.maxFailedLogins(),
                    now.plus(props.lockoutDuration()), now);
            audit.record(user.getId(), null, AuditActions.USER_LOGIN_FAILED, AuditActions.RESOURCE_USER, user.getId(),
                    Map.of("reason", "BAD_PASSWORD"));
            throw invalidCredentials();
        }
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED, "This account is disabled.");
        }
        if (!user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED, "Verify your e-mail address before signing in.");
        }
        cred.recordSuccess();
        loginThrottle.recordSuccess(user.getId(), client.ipAddress());
        if (passwordEncoder.upgradeEncoding(cred.getPasswordHash())) {
            cred.changePassword(passwordEncoder.encode(request.password()), now);
        }
        ClientType clientType = request.clientType() == null ? ClientType.WEB : request.clientType();
        SessionService.IssuedSession issued = sessions.create(user, clientType, client);
        audit.record(user.getId(), null, AuditActions.USER_LOGIN, AuditActions.RESOURCE_USER, user.getId(),
                Map.of("sessionId", issued.sessionId(), "clientType", clientType.name()));
        return issued;
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        VerificationToken token = consumeToken(rawToken, VerificationToken.Purpose.EMAIL_VERIFICATION);
        User user = users.findById(token.getUserId()).orElseThrow(() -> ApiException.notFound("User"));
        user.setEmailVerified(true);
        audit.record(user.getId(), null, AuditActions.USER_EMAIL_VERIFIED, AuditActions.RESOURCE_USER, user.getId(), Map.of());
    }

    @Transactional
    public void resendVerification(String emailInput, ClientInfo client) {
        String email = normalize(emailInput);
        rateLimiter.check("resend-verification", email, 3, Duration.ofHours(1));
        rateLimiter.check("resend-verification-ip", client.ipAddress(), 20, Duration.ofHours(1));
        users.findByEmailIgnoreCase(email)
                .filter(u -> !u.isEmailVerified() && u.isActive())
                .ifPresent(u -> sendVerification(u, clock.instant()));
    }

    @Transactional
    public void requestPasswordReset(String emailInput, ClientInfo client) {
        String email = normalize(emailInput);
        rateLimiter.check("password-reset", email, 3, Duration.ofHours(1));
        rateLimiter.check("password-reset-ip", client.ipAddress(), 20, Duration.ofHours(1));
        users.findByEmailIgnoreCase(email).filter(User::isActive).ifPresent(user -> {
            Instant now = clock.instant();
            verificationTokens.invalidateOutstanding(user.getId(), VerificationToken.Purpose.PASSWORD_RESET, now);
            String raw = Tokens.randomSecret(32);
            verificationTokens.save(new VerificationToken(user.getId(), VerificationToken.Purpose.PASSWORD_RESET,
                    Tokens.sha256(raw), now, now.plus(props.passwordResetTtl())));
            events.publishEvent(new IdentityEvents.PasswordResetRequested(user.getId(), user.getEmail(),
                    user.getDisplayName(), raw));
        });
    }

    @Transactional
    public void confirmPasswordReset(String rawToken, String newPassword) {
        VerificationToken token = consumeToken(rawToken, VerificationToken.Purpose.PASSWORD_RESET);
        User user = users.findById(token.getUserId()).orElseThrow(() -> ApiException.notFound("User"));
        passwordPolicy.validate(newPassword, user.getEmail(), user.getDisplayName());
        Instant now = clock.instant();
        UserCredential cred = credentials.findById(user.getId())
                .orElseGet(() -> credentials.save(new UserCredential(user.getId(), passwordEncoder.encode(newPassword), now)));
        cred.changePassword(passwordEncoder.encode(newPassword), now);
        loginThrottle.clear(user.getId());
        // Completing a reset proves control of the mailbox. External sign-in links attached while the address was
        // still unverified were never proven to belong to the mailbox owner (e.g. an attacker's social account
        // registered on the victim's address): they do not survive the owner taking the account back.
        if (!user.isEmailVerified()) {
            externalLinks.deleteByUserId(user.getId());
        }
        user.setEmailVerified(true);
        sessions.revokeAll(user.getId(), "PASSWORD_RESET");
        audit.record(user.getId(), null, AuditActions.USER_PASSWORD_RESET, AuditActions.RESOURCE_USER, user.getId(), Map.of());
        events.publishEvent(new IdentityEvents.PasswordChanged(user.getId(), user.getEmail(), user.getDisplayName()));
    }

    private void sendVerification(User user, Instant now) {
        verificationTokens.invalidateOutstanding(user.getId(), VerificationToken.Purpose.EMAIL_VERIFICATION, now);
        String raw = Tokens.randomSecret(32);
        verificationTokens.save(new VerificationToken(user.getId(), VerificationToken.Purpose.EMAIL_VERIFICATION,
                Tokens.sha256(raw), now, now.plus(props.emailVerificationTtl())));
        events.publishEvent(new IdentityEvents.EmailVerificationRequested(user.getId(), user.getEmail(),
                user.getDisplayName(), raw));
    }

    private VerificationToken consumeToken(String rawToken, VerificationToken.Purpose purpose) {
        Instant now = clock.instant();
        VerificationToken token = verificationTokens.findByTokenHash(Tokens.sha256(rawToken))
                .filter(t -> t.getPurpose() == purpose && !t.isUsed())
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID, "The link is invalid or was already used."));
        if (token.isExpired(now)) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED, "The link has expired. Request a new one.");
        }
        token.markUsed(now);
        return token;
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid e-mail or password.");
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
