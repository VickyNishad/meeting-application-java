package com.example.meetplatform.identity;

import com.example.meetplatform.identity.event.IdentityEvents;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RecordApplicationEvents
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationEvents events;

    @Autowired
    TestUsers testUsers;

    @Test
    void registerVerifyLoginRefreshAndLogout() throws Exception {
        String email = "ada+" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                        "email", email, "password", "a long and unusual passphrase",
                        "displayName", "Ada Lovelace", "timeZone", "Asia/Kolkata"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerificationRequired").value(true));

        // Unverified accounts cannot sign in.
        login(email, "a long and unusual passphrase")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        String rawToken = events.stream(IdentityEvents.EmailVerificationRequested.class)
                .filter(e -> e.email().equals(email)).findFirst().orElseThrow().rawToken();
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("token", rawToken))))
                .andExpect(status().isNoContent());
        // Tokens are single-use.
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("token", rawToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));

        MvcResult loggedIn = login(email, "a long and unusual passphrase")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().httpOnly("mp_refresh", true))
                .andReturn();
        String access = json.readTree(loggedIn.getResponse().getContentAsString()).get("accessToken").asString();
        Cookie refresh = loggedIn.getResponse().getCookie("mp_refresh");

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"));

        // Cookie-based refresh requires the anti-CSRF header.
        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh))
                .andExpect(status().isForbidden());
        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(refresh)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie rotated = refreshed.getResponse().getCookie("mp_refresh");
        assertThat(rotated.getValue()).isNotEqualTo(refresh.getValue());

        mvc.perform(post("/api/v1/auth/logout").cookie(rotated).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isNoContent());
        // The session is revoked: the old access token stops working (after the short status cache) and refresh fails.
        mvc.perform(post("/api/v1/auth/refresh").cookie(rotated).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsRejectedWithoutRevealingAccountExistence() throws Exception {
        TestUsers.SignedIn user = testUsers.create(mvc, "Grace Hopper");
        login(user.email(), "not the right password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("nobody-" + UUID.randomUUID() + "@example.test", "whatever password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void failedLoginsLockOutOnlyTheOffendingClient() throws Exception {
        TestUsers.SignedIn owner = testUsers.create(mvc, "Targeted Owner");
        for (int i = 0; i < 10; i++) {
            login(owner.email(), "attacker guess " + i, "203.0.113.66")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        // The attacking client is locked out: even the right password is refused from there...
        login(owner.email(), TestUsers.PASSWORD, "203.0.113.66")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("temporarily locked")));
        // ...while the owner, signing in from elsewhere, is not affected.
        login(owner.email(), TestUsers.PASSWORD, "198.51.100.7").andExpect(status().isOk());
    }

    @Test
    void protectedEndpointsRequireAValidToken() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void nativeClientsReceiveRefreshTokenInBodyAndRotationDetectsReuse() throws Exception {
        TestUsers.SignedIn user = testUsers.create(mvc, "Linus");
        JsonNode first = json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", user.email(), "password", TestUsers.PASSWORD, "clientType", "NATIVE"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String refresh1 = first.get("refreshToken").asString();

        JsonNode second = json.readTree(mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("refreshToken", refresh1))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(second.get("refreshToken").asString()).isNotEqualTo(refresh1);

        // Presenting the already-rotated token again is rejected.
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("refreshToken", refresh1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", password))));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password, String clientIp)
            throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", password)))
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                }));
    }
}
