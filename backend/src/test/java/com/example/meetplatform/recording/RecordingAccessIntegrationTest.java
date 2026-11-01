package com.example.meetplatform.recording;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.storage.ObjectStorage;
import com.example.meetplatform.identity.entity.SystemRole;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecordingAccessIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    @Test
    void recordingLifecycleAndAccessControl() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        when(media.startRoomRecording(anyString(), anyString(), anyString(), anyBoolean())).thenReturn("EG_abc");
        when(storage.presignGet(anyString(), anyString(), any(), isNull()))
                .thenReturn(new ObjectStorage.PresignedUrl("https://storage.example.test/signed", Instant.now().plusSeconds(900)));
        when(storage.presignGet(anyString(), anyString(), any(), anyString()))
                .thenReturn(new ObjectStorage.PresignedUrl("https://storage.example.test/download", Instant.now().plusSeconds(900)));

        SignedIn host = users.create(mvc, "Rec Host");
        SignedIn attendee = users.create(mvc, "Attendee");
        SignedIn outsider = users.create(mvc, "Outsider");
        SignedIn admin = users.create(mvc, "Platform Admin", SystemRole.SYSTEM_ADMIN);

        String meetingId = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Recorded", "type", "INSTANT", "timeZone", "UTC"))))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", host.bearer()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", attendee.bearer()))
                .andExpect(status().isOk());

        // Only moderators may record.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", attendee.bearer()))
                .andExpect(status().isForbidden());
        JsonNode recording = json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("STARTING"))
                .andReturn().getResponse().getContentAsString());
        String recordingId = recording.get("id").asString();
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", host.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORDING_ALREADY_ACTIVE"));

        // Not playable until the egress completes.
        mvc.perform(post("/api/v1/recordings/" + recordingId + "/playback-url").header("Authorization", host.bearer()))
                .andExpect(status().isConflict());

        egress("EV_eg_1", MediaWebhookEvent.EgressStatus.ACTIVE);
        egress("EV_eg_2", MediaWebhookEvent.EgressStatus.COMPLETE);
        mvc.perform(get("/api/v1/recordings/" + recordingId).header("Authorization", host.bearer()))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.sizeBytes").value(1048576))
                .andExpect(jsonPath("$.retentionUntil").exists());

        // Participants may play back; outsiders cannot even see it; only managers may download or delete.
        mvc.perform(post("/api/v1/recordings/" + recordingId + "/playback-url").header("Authorization", attendee.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://storage.example.test/signed"));
        mvc.perform(post("/api/v1/recordings/" + recordingId + "/playback-url").header("Authorization", outsider.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/recordings/" + recordingId).header("Authorization", outsider.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/recordings/" + recordingId + "/download-url").header("Authorization", attendee.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/recordings/" + recordingId + "/download-url").header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://storage.example.test/download"));
        mvc.perform(get("/api/v1/recordings").header("Authorization", attendee.bearer()))
                .andExpect(jsonPath("$.items[?(@.id == '" + recordingId + "')]").exists());

        // Admin endpoints are restricted to system administrators.
        mvc.perform(get("/api/v1/admin/stats").header("Authorization", host.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/stats").header("Authorization", admin.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/audit-logs").param("action", "RECORDING_STARTED")
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.resourceId == '" + recordingId + "')]").exists());

        mvc.perform(delete("/api/v1/recordings/" + recordingId).header("Authorization", attendee.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/recordings/" + recordingId).header("Authorization", host.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/recordings/" + recordingId).header("Authorization", host.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void failedEgressIsRecordedAsFailed() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        when(media.startRoomRecording(anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new ApiException(ErrorCode.MEDIA_PROVIDER_UNAVAILABLE, "egress down"));
        SignedIn host = users.create(mvc, "Unlucky Host");
        String meetingId = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Flaky", "type", "INSTANT", "timeZone", "UTC"))))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", host.bearer()));

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", host.bearer()))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", host.bearer()))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].failureReason").exists());
        // A failed attempt does not block a retry.
        when(media.startRoomRecording(anyString(), anyString(), eq("grid"), anyBoolean())).thenReturn("EG_retry");
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", host.bearer()))
                .andExpect(status().isAccepted());
    }

    private void egress(String eventId, MediaWebhookEvent.EgressStatus status) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent(eventId,
                MediaWebhookEvent.Type.EGRESS_UPDATED, "egress_updated", null, null,
                new MediaWebhookEvent.EgressUpdate("EG_abc", status, null, "rec.mp4", 1048576L, 95L,
                        Instant.now().minusSeconds(95), status == MediaWebhookEvent.EgressStatus.COMPLETE ? Instant.now() : null),
                Instant.now()));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }
}
