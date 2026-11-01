package com.example.meetplatform.meeting;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.security.RateLimiter;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Invitation e-mails and reschedule notifications are charged to the host's rate-limit budget. Rate limiting is
 * disabled in the test profile (no Redis), so the limiter is replaced by a mock here; its counting is covered by
 * {@code RateLimiterTest}.
 */
class InvitationRateLimitIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    RateLimiter rateLimiter;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void invitationsAreChargedPerAddressAndRejectedWhenTheBudgetIsSpent() throws Exception {
        SignedIn host = users.create(mvc, "Inviting Host");
        String meetingId = scheduledMeeting(host, List.of("a@example.test"));
        verify(rateLimiter).consume("meeting-invitations", host.id().toString(), 1, 500, Duration.ofHours(1));

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/invitations").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("emails", List.of("b@example.test", "c@example.test", "A@example.test")))))
                .andExpect(status().is2xxSuccessful());
        // Only new addresses count (a@ was already invited).
        verify(rateLimiter).consume("meeting-invitations", host.id().toString(), 2, 500, Duration.ofHours(1));

        doThrow(ApiException.rateLimited(1800)).when(rateLimiter)
                .consume(eq("meeting-invitations"), eq(host.id().toString()), anyLong(), anyInt(), eq(Duration.ofHours(1)));
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/invitations").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("emails", List.of("d@example.test")))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "1800"));
        assertThat(jdbc.queryForObject("select count(*) from meeting_invitations where meeting_id = ?", Long.class,
                UUID.fromString(meetingId))).isEqualTo(3L);
    }

    @Test
    void reschedulesThatNotifyInviteesAreRateLimited() throws Exception {
        SignedIn host = users.create(mvc, "Rescheduling Host");
        String meetingId = scheduledMeeting(host, List.of("guest@example.test"));
        Instant newStart = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);

        doThrow(ApiException.rateLimited(600)).when(rateLimiter)
                .check("meeting-reschedule", host.id().toString(), 20, Duration.ofHours(1));
        mvc.perform(patch("/api/v1/meetings/" + meetingId).header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("startTime", newStart.toString()))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // Rejected as a whole: the schedule did not change and nobody was notified.
        assertThat(jdbc.queryForObject("select start_time from meetings where id = ?", java.sql.Timestamp.class,
                UUID.fromString(meetingId)).toInstant()).isNotEqualTo(newStart);
    }

    private String scheduledMeeting(SignedIn host, List<String> invitees) throws Exception {
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                                "title", "Planning", "type", "SCHEDULED", "timeZone", "UTC",
                                "startTime", Instant.now().plus(1, ChronoUnit.DAYS).toString(),
                                "inviteeEmails", invitees))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return created.get("id").asString();
    }
}
