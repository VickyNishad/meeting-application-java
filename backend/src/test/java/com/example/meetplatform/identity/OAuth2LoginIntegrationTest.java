package com.example.meetplatform.identity;

import com.example.meetplatform.identity.entity.ExternalIdentityLink;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.event.IdentityEvents;
import com.example.meetplatform.identity.repository.ExternalIdentityLinkRepository;
import com.example.meetplatform.identity.repository.UserRepository;
import com.example.meetplatform.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Social sign-in only trusts provider-verified e-mail addresses (the test profile requires e-mail verification).
 * The success handler is invoked directly with the authentication Spring Security would hand it.
 */
@RecordApplicationEvents
class OAuth2LoginIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("oauth2LoginSuccessHandler")
    AuthenticationSuccessHandler successHandler;

    @Autowired
    UserRepository users;

    @Autowired
    ExternalIdentityLinkRepository links;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEvents events;

    @Test
    void preferredUsernameIsNeverUsedAsAnEmailAddress() throws Exception {
        String upn = "victim+" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        MockHttpServletResponse response = signIn("microsoft", claims("ms-" + UUID.randomUUID(),
                Map.of("preferred_username", upn, "name", "Mallory")));
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/login?error=oauth_profile");
        assertThat(users.findByEmailIgnoreCase(upn)).isEmpty();
    }

    @Test
    void unverifiedProviderEmailCreatesNoAccountAndNoSession() throws Exception {
        String email = "unverified+" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        // Microsoft-style token: email present, email_verified absent.
        MockHttpServletResponse response = signIn("microsoft", claims("ms-" + UUID.randomUUID(),
                Map.of("email", email, "name", "Mallory")));
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/login?error=oauth_profile");
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(users.findByEmailIgnoreCase(email)).isEmpty();
    }

    @Test
    void verifiedGoogleSignInCreatesAVerifiedAccountAndASession() throws Exception {
        String email = "ada+" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String subject = "g-" + UUID.randomUUID();
        MockHttpServletResponse response = signIn("google", claims(subject,
                Map.of("email", email, "email_verified", true, "name", "Ada")));
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/oauth/callback");
        assertThat(response.getHeader("Set-Cookie")).contains("mp_refresh=");
        assertThat(users.findByEmailIgnoreCase(email)).hasValueSatisfying(u -> assertThat(u.isEmailVerified()).isTrue());

        // Microsoft's xms_edov claim counts as verification too.
        String msEmail = "grace+" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        assertThat(signIn("microsoft", claims("ms-" + UUID.randomUUID(),
                Map.of("email", msEmail, "xms_edov", true, "name", "Grace"))).getRedirectedUrl())
                .isEqualTo("http://localhost:5173/oauth/callback");
    }

    @Test
    void passwordResetDropsSocialLinksAttachedWhileTheAddressWasUnverified() throws Exception {
        String email = "owner+" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        UUID userId = tx.execute(s -> {
            User user = users.save(new User(email, "Mailbox Owner", "UTC"));
            links.save(new ExternalIdentityLink(user.getId(), "microsoft", "attacker-subject-" + UUID.randomUUID(),
                    email, Instant.now()));
            return user.getId();
        });

        mvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", email))))
                .andExpect(status().is2xxSuccessful());
        String raw = events.stream(IdentityEvents.PasswordResetRequested.class)
                .filter(e -> e.email().equals(email)).findFirst().orElseThrow().rawToken();
        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("token", raw, "newPassword", "a brand new long passphrase"))))
                .andExpect(status().is2xxSuccessful());

        assertThat(jdbc.queryForObject("select count(*) from external_identity_links where user_id = ?", Long.class,
                userId)).isZero();
        assertThat(users.findById(userId)).hasValueSatisfying(u -> assertThat(u.isEmailVerified()).isTrue());
    }

    private MockHttpServletResponse signIn(String registrationId, OidcIdToken idToken) throws Exception {
        var authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                new DefaultOidcUser(authorities, idToken), authorities, registrationId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
        return response;
    }

    private static OidcIdToken claims(String subject, Map<String, Object> extra) {
        Map<String, Object> claims = new HashMap<>(extra);
        claims.put("sub", subject);
        return new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(300), claims);
    }
}
