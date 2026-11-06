package com.example.meetplatform.file;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.storage.ObjectStorage;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared files: who may delete one, the database and storage staying in step, and an object that has gone missing. */
class FileDeleteIntegrationTest extends AbstractIntegrationTest {

    private static final String TOKEN = "X-Participant-Token";

    @Autowired
    TestUsers users;

    private SignedIn host;
    private SignedIn alice;
    private SignedIn bob;
    private String meetingId;
    private String hostPid;
    private String alicePid;
    private String bobPid;

    @BeforeEach
    void meetingWithThreePeople() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        host = users.create(mvc, "File Host");
        alice = users.create(mvc, "Alice Files");
        bob = users.create(mvc, "Bob Files");
        meetingId = meeting(host);
        hostPid = join(meetingId, host).get("participantId").asString();
        alicePid = join(meetingId, alice).get("participantId").asString();
        bobPid = join(meetingId, bob).get("participantId").asString();
    }

    @Test
    void theUploaderOrTheHostCanDeleteOthersCannotAndTheFileThenDisappears() throws Exception {
        String one = upload(alice, alicePid);
        String two = upload(alice, alicePid);

        mvc.perform(asUser(delete(file(one)), bob).param("participantId", bobPid)).andExpect(status().isForbidden());
        verify(storage, never()).delete(anyString(), anyString());

        mvc.perform(asUser(delete(file(one)), alice).param("participantId", alicePid)).andExpect(status().isNoContent());
        mvc.perform(asUser(delete(file(two)), host).param("participantId", hostPid)).andExpect(status().isNoContent());

        mvc.perform(asUser(get("/api/v1/meetings/" + meetingId + "/files"), host).param("participantId", hostPid))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(asUser(post(file(one) + "/download-url"), host).param("participantId", hostPid))
                .andExpect(status().isNotFound());
        // Deleting it twice is a plain 404.
        mvc.perform(asUser(delete(file(one)), alice).param("participantId", alicePid)).andExpect(status().isNotFound());
    }

    @Test
    void aForgedParticipantOrAnotherMeetingsFileIsRefused() throws Exception {
        String id = upload(alice, alicePid);
        // Bob cannot act as Alice by naming her participant id.
        mvc.perform(asUser(delete(file(id)), bob).param("participantId", alicePid)).andExpect(status().is4xxClientError());
        // A wrong participant secret, and no credentials at all.
        mvc.perform(delete(file(id)).param("participantId", alicePid).header(TOKEN, "wrong"))
                .andExpect(status().is4xxClientError());
        mvc.perform(delete(file(id)).param("participantId", alicePid)).andExpect(status().is4xxClientError());

        // The host of another meeting cannot reach this file through that meeting.
        String other = meeting(host);
        String otherPid = join(other, host).get("participantId").asString();
        mvc.perform(asUser(delete("/api/v1/meetings/" + other + "/files/" + id), host).param("participantId", otherPid))
                .andExpect(status().isNotFound());

        verify(storage, never()).delete(anyString(), anyString());
    }

    @Test
    void whenStorageFailsTheFileStaysListedSoTheDatabaseNeverDisagrees() throws Exception {
        String id = upload(alice, alicePid);
        doThrow(new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "down")).when(storage).delete(anyString(), anyString());

        mvc.perform(asUser(delete(file(id)), alice).param("participantId", alicePid))
                .andExpect(status().isServiceUnavailable());

        mvc.perform(asUser(get("/api/v1/meetings/" + meetingId + "/files"), alice).param("participantId", alicePid))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aMissingObjectIsAClearNotFoundNotADeadLink() throws Exception {
        String id = upload(alice, alicePid);
        when(storage.exists(anyString(), anyString())).thenReturn(false);

        mvc.perform(asUser(post(file(id) + "/download-url"), bob).param("participantId", bobPid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("This file is no longer available."));
        verify(storage, never()).presignGet(anyString(), anyString(), any(), any());

        when(storage.exists(anyString(), anyString())).thenReturn(true);
        when(storage.presignGet(anyString(), anyString(), any(), eq("note.txt")))
                .thenReturn(new ObjectStorage.PresignedUrl("https://example.test/signed", Instant.now().plusSeconds(60)));
        mvc.perform(asUser(post(file(id) + "/download-url"), bob).param("participantId", bobPid))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://example.test/signed"));
    }

    private String file(String id) {
        return "/api/v1/meetings/" + meetingId + "/files/" + id;
    }

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder r, SignedIn user) {
        return r.header("Authorization", user.bearer());
    }

    private String upload(SignedIn user, String pid) throws Exception {
        MockMultipartFile text = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        return json.readTree(mvc.perform(multipart("/api/v1/meetings/" + meetingId + "/files").file(text)
                        .param("participantId", pid).header("Authorization", user.bearer()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String meeting(SignedIn owner) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Files", "type", "INSTANT", "timeZone", "UTC"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private JsonNode join(String meeting, SignedIn user) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings/" + meeting + "/join").header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
