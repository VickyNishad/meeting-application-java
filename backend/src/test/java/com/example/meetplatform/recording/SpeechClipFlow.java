package com.example.meetplatform.recording;

import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The speaking-clip HTTP flow (meeting, join, start, chunks, finish, access URL) for the storage-mode tests. */
final class SpeechClipFlow {

    static final byte[] WEBM_HEAD = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0x42, (byte) 0x86, (byte) 0x81, 0x01};
    private static final String TOKEN = "X-Participant-Token";

    private final MockMvc mvc;
    private final JsonMapper json;
    private final MediaService media;
    private final SignedIn host;

    SpeechClipFlow(MockMvc mvc, JsonMapper json, MediaService media, SignedIn host) {
        this.mvc = mvc;
        this.json = json;
        this.media = media;
        this.host = host;
    }

    record Who(String meetingId, String pid, String secret, SignedIn user) {
        MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r) {
            return user != null ? r.header("Authorization", user.bearer()) : r.header(TOKEN, secret);
        }
    }

    String meeting(String title) throws Exception {
        return read(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", title, "type", "INSTANT", "timeZone", "UTC",
                        "autoRecord", true))))
                .andExpect(status().isCreated())).get("id").asString();
    }

    Who joinAsHost(String meetingId) throws Exception {
        JsonNode j = read(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                .header("Authorization", host.bearer())).andExpect(status().isOk()));
        connected(j);
        return new Who(meetingId, j.get("participantId").asString(), null, host);
    }

    Who joinAsGuest(String meetingId, String name) throws Exception {
        JsonNode j = read(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("displayName", name))))
                .andExpect(status().isOk()));
        Who g = new Who(meetingId, j.get("participantId").asString(), j.get("waitToken").asString(), null);
        JsonNode room = read(mvc.perform(get("/api/v1/meetings/" + meetingId + "/join-status")
                .param("participantId", g.pid()).header(TOKEN, g.secret())).andExpect(status().isOk()));
        connected(room);
        return g;
    }

    JsonNode start(Who who) throws Exception {
        return read(mvc.perform(who.auth(post("/api/v1/meetings/" + who.meetingId() + "/speech-recordings"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("participantId", who.pid(), "format", "audio/webm;codecs=opus"))))
                .andExpect(status().isCreated()));
    }

    ResultActions chunk(Who who, String id, int seq, byte[] bytes) throws Exception {
        return mvc.perform(who.auth(put("/api/v1/meetings/" + who.meetingId() + "/speech-recordings/" + id + "/chunks/" + seq))
                .param("participantId", who.pid()).contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes));
    }

    ResultActions finish(Who who, String id, int chunks) throws Exception {
        return mvc.perform(who.auth(post("/api/v1/meetings/" + who.meetingId() + "/speech-recordings/" + id + "/finish"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("participantId", who.pid(), "chunks", chunks))));
    }

    /** Starts a clip and uploads {@code chunks} chunks (the first carries the WebM header); not finished. */
    String recordUnfinished(Who who, int chunks) throws Exception {
        String id = start(who).get("id").asString();
        for (int i = 0; i < chunks; i++) {
            chunk(who, id, i, i == 0 ? WEBM_HEAD : new byte[] {(byte) i, 0, 1}).andExpect(status().isOk());
        }
        return id;
    }

    JsonNode recordClip(Who who, int chunks) throws Exception {
        String id = recordUnfinished(who, chunks);
        return read(finish(who, id, chunks).andExpect(status().isOk()));
    }

    /** The dashboard's access-url call as the host, for tests that expect it to fail. */
    ResultActions requestAccess(String clipId) throws Exception {
        return mvc.perform(post("/api/v1/speech-recordings/" + clipId + "/access-url").header("Authorization", host.bearer()));
    }

    /** The signed content URL from the dashboard's access-url call, as a path + query usable with MockMvc. */
    String accessPath(String clipId, boolean download) throws Exception {
        String url = read(mvc.perform(post("/api/v1/speech-recordings/" + clipId + "/access-url")
                .param("download", String.valueOf(download)).header("Authorization", host.bearer()))
                .andExpect(status().isOk())).get("url").asString();
        java.net.URI uri = java.net.URI.create(url);
        return uri.getRawPath() + "?" + uri.getRawQuery();
    }

    JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private void connected(JsonNode join) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_" + UUID.randomUUID(),
                MediaWebhookEvent.Type.PARTICIPANT_JOINED, "participant_joined", join.get("roomName").asString(),
                join.get("participantId").asString(), null, Instant.now(), "PA_" + UUID.randomUUID()));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }
}
