package com.example.meetplatform.calendar;

import com.example.meetplatform.calendar.adapter.CalendarProvider;
import com.example.meetplatform.calendar.adapter.GoogleCalendarProvider;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The calendar OAuth state is bound to the browser that started the flow and usable once: a victim lured to an
 * attacker's consent URL (no matching cookie) cannot end up with the victim's calendar linked to the attacker.
 */
@TestPropertySource(properties = {
        "app.calendar.encryption-key=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "app.calendar.google.client-id=test-client-id",
        "app.calendar.google.client-secret=test-client-secret"})
class CalendarOAuthStateIntegrationTest extends AbstractIntegrationTest {

    private static final String COOKIE = "mp_calendar_oauth";

    @MockitoSpyBean
    GoogleCalendarProvider google;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void stateIsBoundToTheInitiatingBrowserAndSingleUse() throws Exception {
        doReturn(new CalendarProvider.TokenSet("access", "refresh", Instant.now().plusSeconds(3600), "me@gmail.test",
                "calendar")).when(google).exchangeCode(eq("good-code"), anyString());
        SignedIn attacker = users.create(mvc, "Calendar Attacker");

        MockHttpServletResponse authorize = mvc.perform(post("/api/v1/calendar/connections/google/authorize")
                        .header("Authorization", attacker.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        String setCookie = authorize.getHeader("Set-Cookie");
        assertThat(setCookie).contains(COOKIE + "=").contains("HttpOnly").contains("SameSite=Lax")
                .contains("Path=/api/v1/calendar/oauth/callback");
        Cookie nonce = authorize.getCookie(COOKIE);
        String url = json.readTree(authorize.getContentAsString()).get("authorizationUrl").asString();
        String state = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");

        // The victim's browser follows the attacker's consent URL: it has no (or another) nonce cookie.
        callback(state, null).andExpect(header().string("Location", "http://localhost:5173/settings?calendar=error"));
        callback(state, new Cookie(COOKIE, "someone-elses-nonce"))
                .andExpect(header().string("Location", "http://localhost:5173/settings?calendar=error"));
        verify(google, never()).exchangeCode(anyString(), anyString());

        // The initiating browser completes the flow, and clears its cookie.
        callback(state, nonce)
                .andExpect(header().string("Location", "http://localhost:5173/settings?calendar=connected"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertThat(jdbc.queryForObject("select count(*) from calendar_connections where user_id = ?", Long.class,
                attacker.id())).isEqualTo(1L);

        // Replaying the same state and cookie fails: single use.
        callback(state, nonce).andExpect(header().string("Location", "http://localhost:5173/settings?calendar=error"));
        verify(google, times(1)).exchangeCode(anyString(), anyString());
    }

    private org.springframework.test.web.servlet.ResultActions callback(String state, Cookie cookie) throws Exception {
        var request = get("/api/v1/calendar/oauth/callback").param("code", "good-code").param("state", state);
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mvc.perform(request).andExpect(status().isFound());
    }
}
