package com.example.meetplatform.analytics;

import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Speaking time: reported by the present moderator who joined first, counted once per batch, bounded, limited to
 * participants of the session, and shown only to the host (and a present co-host, for the live session).
 */
class SpeakerStatsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    private SignedIn host;
    private SignedIn alice;
    private SignedIn outsider;

    @BeforeEach
    void setUp() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        host = users.create(mvc, "Speaker Host");
        alice = users.create(mvc, "Speaker Alice");
        outsider = users.create(mvc, "Speaker Outsider");
    }

    @Test
    void theElectedModeratorsBatchesAreCountedOnceAndOnlyForThisSession() throws Exception {
        String meetingId = meeting();
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        joined(roomName, hostPid);
        joined(roomName, alicePid);
        String strangerPid = join(meeting(), outsider).get("participantId").asString();

        Map<String, Object> batch = Map.of("participantId", hostPid, "batchSeq", 1L, "entries", List.of(
                Map.of("participantId", hostPid, "speakingMs", 4000, "turns", 1),
                Map.of("participantId", alicePid, "speakingMs", 6000, "turns", 2),
                Map.of("participantId", strangerPid, "speakingMs", 9000, "turns", 1)));
        report(meetingId, host, batch).andExpect(status().isNoContent());
        report(meetingId, host, batch).andExpect(status().isNoContent()); // retry of the same batch
        report(meetingId, host, Map.of("participantId", hostPid, "batchSeq", 2L, "entries", List.of(
                Map.of("participantId", alicePid, "speakingMs", 1000, "turns", 0))))
                .andExpect(status().isNoContent());

        JsonNode stats = stats(meetingId, host);
        assertThat(stats.get("totalSpeakingMs").asLong()).isEqualTo(11_000L);
        JsonNode speakers = stats.get("speakers");
        assertThat(speakers.size()).isEqualTo(2);
        assertThat(speakers.get(0).get("displayName").asString()).isEqualTo("Speaker Alice");
        assertThat(speakers.get(0).get("speakingMs").asLong()).isEqualTo(7000L);
        assertThat(speakers.get(0).get("turns").asInt()).isEqualTo(2);
        assertThat(speakers.get(1).get("speakingMs").asLong()).isEqualTo(4000L);
    }

    @Test
    void onlyTheElectedModeratorMayReportAndAttendeesNeverMay() throws Exception {
        String meetingId = meeting();
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        joined(roomName, hostPid);
        joined(roomName, alicePid);

        report(meetingId, alice, Map.of("participantId", alicePid, "batchSeq", 1L, "entries", List.of()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + alicePid + "/role")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("role", "CO_HOST"))))
                .andExpect(status().isNoContent());
        // The host joined first: the co-host's client is told someone else reports.
        report(meetingId, alice, Map.of("participantId", alicePid, "batchSeq", 1L, "entries", List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPEAKER_REPORTER_MISMATCH"));
        // Someone else's participant id is refused outright.
        report(meetingId, alice, Map.of("participantId", hostPid, "batchSeq", 1L, "entries", List.of()))
                .andExpect(status().isForbidden());
    }

    @Test
    void valuesAreBounded() throws Exception {
        String meetingId = meeting();
        JsonNode hostJoin = join(meetingId, host);
        String hostPid = hostJoin.get("participantId").asString();
        joined(hostJoin.get("roomName").asString(), hostPid);
        report(meetingId, host, Map.of("participantId", hostPid, "batchSeq", 1L, "entries", List.of(
                Map.of("participantId", hostPid, "speakingMs", 3_600_000, "turns", 1))))
                .andExpect(status().isBadRequest());
        report(meetingId, host, Map.of("participantId", hostPid, "batchSeq", 2L, "entries", List.of(
                Map.of("participantId", hostPid, "speakingMs", -5, "turns", 1))))
                .andExpect(status().isBadRequest());
    }

    /** QA-B9: repeating one participant in a batch cannot get around the per-batch limit. */
    @Test
    void repeatedEntriesAreMergedAndCappedPerBatch() throws Exception {
        String meetingId = meeting();
        JsonNode hostJoin = join(meetingId, host);
        String hostPid = hostJoin.get("participantId").asString();
        joined(hostJoin.get("roomName").asString(), hostPid);
        List<Map<String, Object>> repeated = java.util.Collections.nCopies(300,
                Map.of("participantId", hostPid, "speakingMs", 30_000, "turns", 1));
        report(meetingId, host, Map.of("participantId", hostPid, "batchSeq", 1L, "entries", repeated))
                .andExpect(status().isNoContent());
        JsonNode stats = stats(meetingId, host);
        assertThat(stats.get("totalSpeakingMs").asLong()).isEqualTo(30_000L);
        assertThat(stats.get("speakers").get(0).get("turns").asInt()).isEqualTo(100);
    }

    @Test
    void onlyTheHostReadsStatsAndAPresentCoHostOnlyTheLiveSession() throws Exception {
        String meetingId = meeting();
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        joined(roomName, hostJoin.get("participantId").asString());
        joined(roomName, alicePid);

        mvc.perform(get("/api/v1/meetings/" + meetingId + "/speaker-stats").header("Authorization", alice.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/speaker-stats").header("Authorization", outsider.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + alicePid + "/role")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("role", "CO_HOST"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/speaker-stats").header("Authorization", alice.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void dashboardSummarisesOwnMeetingsOnly() throws Exception {
        String meetingId = meeting();
        JsonNode hostJoin = join(meetingId, host);
        String hostPid = hostJoin.get("participantId").asString();
        joined(hostJoin.get("roomName").asString(), hostPid);
        report(meetingId, host, Map.of("participantId", hostPid, "batchSeq", 1L, "entries", List.of(
                Map.of("participantId", hostPid, "speakingMs", 5000, "turns", 1))))
                .andExpect(status().isNoContent());

        JsonNode mine = json.readTree(mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode highlight = mine.get("speakingHighlights").get(0);
        assertThat(highlight.get("meetingId").asString()).isEqualTo(meetingId);
        assertThat(highlight.get("totalSpeakingMs").asLong()).isEqualTo(5000L);
        assertThat(highlight.get("topSpeakers").get(0).get("sharePercent").asDouble()).isEqualTo(100.0);
        assertThat(mine.has("recordingsInProgress")).isTrue();
        assertThat(mine.has("storageUsedBytes")).isTrue();

        JsonNode theirs = json.readTree(mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", outsider.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(theirs.get("speakingHighlights").size()).isZero();
        mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private String meeting() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Speakers", "type", "INSTANT", "timeZone", "UTC"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private JsonNode join(String meetingId, SignedIn user) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions report(String meetingId, SignedIn user,
                                                                       Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/meetings/" + meetingId + "/speaker-stats").header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private JsonNode stats(String meetingId, SignedIn user) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/meetings/" + meetingId + "/speaker-stats")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void joined(String roomName, String participantId) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_" + UUID.randomUUID(),
                MediaWebhookEvent.Type.PARTICIPANT_JOINED, "participant_joined", roomName, participantId, null,
                Instant.now(), "PA_" + participantId));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }
}
