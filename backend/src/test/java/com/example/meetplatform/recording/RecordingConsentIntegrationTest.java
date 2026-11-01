package com.example.meetplatform.recording;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Consent: recording indicators reach the room before capture starts, and nothing records if they cannot. */
class RecordingConsentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    private SignedIn host;
    private String meetingId;
    private String roomName;
    private String hostParticipantId;

    @BeforeEach
    void liveMeeting() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        when(media.startRoomRecording(anyString(), anyString(), anyString(), anyBoolean())).thenReturn("EG_consent");
        host = users.create(mvc, "Consent Host");
        meetingId = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Consent", "type", "INSTANT", "timeZone", "UTC"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        JsonNode join = json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        roomName = join.get("roomName").asString();
        hostParticipantId = join.get("participantId").asString();
    }

    @Test
    void cloudRecordingIndicatorIsDeliveredBeforeCaptureStarts() throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", host.bearer()))
                .andExpect(status().isAccepted());
        InOrder order = inOrder(media);
        order.verify(media).sendData(eq(roomName), eq("recording"), any(), anyList());
        order.verify(media).startRoomRecording(eq(roomName), anyString(), anyString(), anyBoolean());
    }

    @Test
    void cloudRecordingDoesNotStartWhenTheRoomCannotBeNotified() throws Exception {
        doThrow(new ApiException(ErrorCode.MEDIA_PROVIDER_UNAVAILABLE, "data channel down"))
                .when(media).sendData(eq(roomName), eq("recording"), any(), anyList());

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", host.bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEDIA_PROVIDER_UNAVAILABLE"));
        verify(media, never()).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", host.bearer()))
                .andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    @Test
    void localRecordingNoticeFailsWhenTheRoomCannotBeNotified() throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/local-recordings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", hostParticipantId, "state", "STARTED"))))
                .andExpect(status().isNoContent());
        // Delivered at once (unnumbered), then again numbered after commit for the room-state bookkeeping (QA-B2).
        verify(media, org.mockito.Mockito.times(2)).sendData(eq(roomName), eq("recording"), any(), anyList());

        doThrow(new ApiException(ErrorCode.MEDIA_PROVIDER_UNAVAILABLE, "data channel down"))
                .when(media).sendData(eq(roomName), eq("recording"), any(), anyList());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/local-recordings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", hostParticipantId, "state", "STARTED"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEDIA_PROVIDER_UNAVAILABLE"));
    }
}
