package com.example.meetplatform.file;

import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileUploadIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    @Test
    void contentThatDoesNotMatchTheDeclaredTypeIsRejected() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        SignedIn host = users.create(mvc, "Sharing Host");
        String meetingId = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Files", "type", "INSTANT", "timeZone", "UTC"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        JsonNode join = json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String participantId = join.get("participantId").asString();

        MockMultipartFile disguised = new MockMultipartFile("file", "cat.png", "image/png",
                "<html><script>alert(document.cookie)</script></html>".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/meetings/" + meetingId + "/files").file(disguised)
                        .param("participantId", participantId).header("Authorization", host.bearer()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
        verify(storage, never()).put(anyString(), anyString(), any(), anyLong(), anyString());

        MockMultipartFile png = new MockMultipartFile("file", "cat.png", "image/png",
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13});
        mvc.perform(multipart("/api/v1/meetings/" + meetingId + "/files").file(png)
                        .param("participantId", participantId).header("Authorization", host.bearer()))
                .andExpect(status().isCreated());
        verify(storage).put(anyString(), anyString(), any(), eq(12L), eq("image/png"));
    }
}
