package com.example.meetplatform.recording;

import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The dashboard's "Speaking clips" and "Storage" tiles count clips that still have a file, and only those (QA F5). */
class DashboardSpeechClipCountsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void deletedAndFailedClipsAreNotCountedCompletedAndIncompleteOnesAre() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        SignedIn host = users.create(mvc, "Dashboard Clip Host");
        SpeechClipFlow flow = new SpeechClipFlow(mvc, json, media, host);
        String meeting = flow.meeting("Dashboard clips");
        flow.joinAsHost(meeting);
        SpeechClipFlow.Who guest = flow.joinAsGuest(meeting, "Counted Guest");
        UUID completed = clip(flow, guest);
        UUID incomplete = clip(flow, guest);
        UUID failed = clip(flow, guest);
        UUID deleted = clip(flow, guest);
        UUID deletedIncomplete = clip(flow, guest);
        jdbc.update("update speech_recordings set file_size = 1000 where id = ?", completed);
        jdbc.update("update speech_recordings set file_size = 200, status = 'INCOMPLETE' where id = ?", incomplete);
        jdbc.update("update speech_recordings set file_size = 40000, status = 'FAILED' where id = ?", failed);
        jdbc.update("update speech_recordings set file_size = 50000, deleted_at = now() where id = ?", deleted);
        jdbc.update("update speech_recordings set file_size = 60000, status = 'INCOMPLETE', deleted_at = now() where id = ?",
                deletedIncomplete);

        JsonNode summary = json.readTree(mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(summary.get("speechClipCount").asLong()).isEqualTo(2);
        assertThat(summary.get("speechClipBytes").asLong()).isEqualTo(1200);
        // Whole-meeting recordings are a separate number and this meeting has none.
        assertThat(summary.get("storageUsedBytes").asLong()).isZero();
    }

    private UUID clip(SpeechClipFlow flow, SpeechClipFlow.Who who) throws Exception {
        return UUID.fromString(flow.recordClip(who, 1).get("id").asString());
    }
}
