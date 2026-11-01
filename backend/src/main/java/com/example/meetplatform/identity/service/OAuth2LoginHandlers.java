package com.example.meetplatform.identity.service;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.audit.AuditService;
import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.identity.dto.AuthDtos.ClientType;
import com.example.meetplatform.identity.entity.ExternalIdentityLink;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.repository.ExternalIdentityLinkRepository;
import com.example.meetplatform.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;

/**
 * Completes Google / Microsoft sign-in: links or creates the local account, opens a normal refresh-token
 * session (cookie) and redirects to the SPA, which then calls /auth/refresh to obtain an access token.
 *
 * <p>Only the provider's {@code email} claim is used as an e-mail address (never {@code preferred_username},
 * which is a login name that need not be a mailbox). When the app requires verified e-mail addresses
 * ({@code app.auth.require-email-verification}), accounts are only created, linked and signed in on an address
 * the provider asserts is verified ({@code email_verified}; for Microsoft also the optional {@code xms_edov}
 * claim). Existing local accounts are never linked on an unverified address.
 */
@Configuration(proxyBeanMethods = false)
public class OAuth2LoginHandlers {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginHandlers.class);

    @Bean
    AuthenticationSuccessHandler oauth2LoginSuccessHandler(UserRepository users, ExternalIdentityLinkRepository links,
                                                          SessionService sessions, RefreshCookies cookies,
                                                          AuditService audit, TransactionTemplate tx,
                                                          AppProperties properties, Clock clock) {
        boolean requireVerified = properties.auth().requireEmailVerification();
        return (request, response, authentication) -> {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            String provider = token.getAuthorizedClientRegistrationId();
            Identity identity = extract(provider, token.getPrincipal());
            if (identity.subject() == null) {
                response.sendRedirect(properties.publicUrl() + "/login?error=oauth_profile");
                return;
            }
            Outcome outcome = tx.execute(status -> {
                var linked = links.findByProviderAndSubject(provider, identity.subject())
                        .flatMap(l -> users.findById(l.getUserId()));
                User user;
                if (linked.isPresent()) {
                    user = linked.get();
                    if (!user.isEmailVerified() && identity.emailVerified()
                            && user.getEmail().equalsIgnoreCase(identity.email())) {
                        user.setEmailVerified(true);
                    }
                } else {
                    if (identity.email() == null || (requireVerified && !identity.emailVerified())) {
                        return Outcome.failed("oauth_profile");
                    }
                    var byEmail = users.findByEmailIgnoreCase(identity.email());
                    if (byEmail.isPresent() && !identity.emailVerified()) {
                        return Outcome.failed("oauth_account");
                    }
                    user = byEmail.orElseGet(() -> {
                        User created = new User(identity.email(), identity.name(), "UTC");
                        created.setEmailVerified(identity.emailVerified());
                        return users.save(created);
                    });
                    if (identity.emailVerified()) {
                        user.setEmailVerified(true);
                    }
                    links.save(new ExternalIdentityLink(user.getId(), provider, identity.subject(), identity.email(),
                            clock.instant()));
                }
                // Same rule as password sign-in: no session for an account whose address was never verified.
                if (requireVerified && !user.isEmailVerified()) {
                    return Outcome.failed("oauth_profile");
                }
                if (!user.isActive()) {
                    return Outcome.failed("oauth_account");
                }
                SessionService.IssuedSession s = sessions.create(user, ClientType.WEB, ClientInfo.from(request));
                audit.record(user.getId(), null, AuditActions.USER_LOGIN, AuditActions.RESOURCE_USER, user.getId(),
                        Map.of("provider", provider, "sessionId", s.sessionId()));
                return new Outcome(s, null);
            });
            // The HTTP session only carried the OAuth2 authorization request; it is not used for API auth.
            var httpSession = request.getSession(false);
            if (httpSession != null) {
                httpSession.invalidate();
            }
            if (outcome == null || outcome.session() == null) {
                String error = outcome == null ? "oauth_account" : outcome.error();
                log.info("OAuth2 sign-in via {} refused: {}", provider, error);
                response.sendRedirect(properties.publicUrl() + "/login?error=" + error);
                return;
            }
            response.addHeader(HttpHeaders.SET_COOKIE, cookies.issue(outcome.session().refreshToken()).toString());
            response.sendRedirect(properties.publicUrl() + "/oauth/callback");
        };
    }

    /**
     * ID-token validation for social login. Microsoft is configured with explicit endpoints (no issuer-uri, see
     * application-oauth.yml) because multi-tenant authorities have no fixed issuer; its issuer is checked by
     * {@link MicrosoftIssuerValidator} instead. Picked up by Spring Security's OAuth2 login as the
     * {@code JwtDecoderFactory<ClientRegistration>} bean.
     */
    @Bean
    JwtDecoderFactory<ClientRegistration> oidcIdTokenDecoderFactory(
            @Value("${app.oauth.microsoft-tenant:common}") String microsoftTenant) {
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(registration -> "microsoft".equals(registration.getRegistrationId())
                ? new DelegatingOAuth2TokenValidator<>(new OidcIdTokenValidator(registration),
                        new MicrosoftIssuerValidator(microsoftTenant))
                : new OidcIdTokenValidator(registration));
        return factory;
    }

    @Bean
    AuthenticationFailureHandler oauth2LoginFailureHandler(AppProperties properties) {
        return (request, response, exception) -> {
            log.warn("OAuth2 login failed: {}", exception.getMessage());
            response.sendRedirect(properties.publicUrl() + "/login?error=oauth");
        };
    }

    static Identity extract(String registrationId, OAuth2User principal) {
        if (principal instanceof OidcUser oidc) {
            String email = oidc.getEmail();
            boolean verified = Boolean.TRUE.equals(oidc.getEmailVerified())
                    // Microsoft omits email_verified; xms_edov ("email domain owner verified") is its equivalent
                    // when configured as an optional claim of the app registration.
                    || ("microsoft".equals(registrationId) && Boolean.TRUE.equals(oidc.getClaimAsBoolean("xms_edov")));
            String name = oidc.getFullName() != null ? oidc.getFullName() : email;
            return new Identity(oidc.getSubject(), normalize(email), email != null && verified, truncate(name));
        }
        Map<String, Object> attrs = principal.getAttributes();
        String email = attrs.get("email") instanceof String s ? s : null;
        Object verified = attrs.get("email_verified");
        String name = attrs.get("name") instanceof String n ? n : email;
        return new Identity(principal.getName(), normalize(email), email != null && Boolean.TRUE.equals(verified),
                truncate(name));
    }

    private static String normalize(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String name) {
        return name == null ? "User" : name.length() > 120 ? name.substring(0, 120) : name;
    }

    record Identity(String subject, String email, boolean emailVerified, String name) {
    }

    private record Outcome(SessionService.IssuedSession session, String error) {

        static Outcome failed(String error) {
            return new Outcome(null, error);
        }
    }
}
