package com.example.meetplatform.recording;

import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The default in every real environment: no recorder deployed, so cloud recording never starts or leaves a failed row. */
@TestPropertySource(properties = "app.recording.cloud-enabled=false")
class CloudRecordingDisabledIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    @Test
    void nothingStartsNoRowIsLeftAndSpeakingClipsStillWork() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        SignedIn host = users.create(mvc, "No Cloud Host");
        String meetingId = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "No cloud", "type", "INSTANT", "timeZone", "UTC", "autoRecord", true))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        JsonNode join = json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String pid = join.get("participantId").asString();

        // A manual start is refused.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", host.bearer()))
                .andExpect(status().isConflict());

        // Speech in an auto-recorded meeting still makes a clip, and starts no cloud recording.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/speech-recordings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", pid, "format", "audio/webm"))))
                .andExpect(status().isCreated());

        verify(media, never()).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());
        JsonNode rows = json.readTree(mvc.perform(get("/api/v1/meetings/" + meetingId + "/recordings")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(rows.size()).isZero();

        // The client is told, so it does not offer the control.
        JsonNode state = json.readTree(mvc.perform(get("/api/v1/meetings/" + meetingId + "/room-state")
                        .param("participantId", pid).header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(state.get("cloudRecordingAvailable").asBoolean()).isFalse();
    }
}
