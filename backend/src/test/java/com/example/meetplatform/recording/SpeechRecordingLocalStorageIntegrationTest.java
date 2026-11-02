package com.example.meetplatform.recording;

import com.example.meetplatform.recording.SpeechClipFlow.Who;
import com.example.meetplatform.recording.service.SpeechClipObjectStore;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RECORDING_STORAGE_TYPE=LOCAL (the default): clips are saved under RECORDING_LOCAL_PATH exactly as before and
 * streamed by the API; nothing goes to S3. The folder used here is inside the project (on the D: drive on the
 * development PC), and the dev profile's default is checked to be D:/MeetingApplication/Recordings.
 */
class SpeechRecordingLocalStorageIntegrationTest extends AbstractIntegrationTest {

    private static final Path ROOT = Path.of("target", "it-local-recordings-" + UUID.randomUUID()).toAbsolutePath();

    @DynamicPropertySource
    static void local(DynamicPropertyRegistry registry) {
        registry.add("app.speech-recordings.storage-type", () -> "LOCAL");
        registry.add("app.speech-recordings.storage-path", ROOT::toString);
    }

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SpeechClipObjectStore objectStore;

    private SpeechClipFlow flow;

    @BeforeEach
    void setUp() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        flow = new SpeechClipFlow(mvc, json, media, users.create(mvc, "Local Clip Host"));
    }

    @Test
    void clipsAreSavedOnTheLocalDiskWithTheExistingLayoutAndStreamedByTheApi() throws Exception {
        assertThat(objectStore.enabled()).isFalse();
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            // The project (and so this test folder) lives on the D: drive of the development PC.
            assertThat(ROOT.getRoot().toString()).isEqualToIgnoringCase(Path.of("D:\\").toString());
        }
        String m = flow.meeting("Sprint demo");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Vicky Kumar");
        JsonNode clip = flow.recordClip(guest, 2);

        assertThat(clip.get("status").asString()).isEqualTo("COMPLETED");
        String path = jdbc.queryForObject("select storage_path from speech_recordings where id = ?", String.class,
                UUID.fromString(clip.get("id").asString()));
        String today = jdbc.queryForObject("select to_char(now() at time zone 'UTC', 'DD-MM-YYYY')", String.class);
        assertThat(path).matches(today + "/Sprint_demo/Vicky_Kumar/\\d{12}_[0-9a-f]{6}\\.webm");
        Path file = ROOT.resolve(path);
        assertThat(file).exists();
        assertThat(Files.size(file)).isEqualTo(clip.get("fileSize").asLong());

        // Playback is streamed by the API from disk, as before (no redirect).
        MockHttpServletResponse play = mvc.perform(get(flow.accessPath(clip.get("id").asString(), false)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(play.getContentAsByteArray()).startsWith(SpeechClipFlow.WEBM_HEAD);
        assertThat(play.getHeader("Content-Disposition")).startsWith("inline");
    }

    @Test
    void theDevProfileDefaultsToTheWindowsDDriveFolderAndHonoursTheEnvironment() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader().load("dev", new ClassPathResource("application-dev.yml"))
                .forEach(env.getPropertySources()::addLast);

        assertThat(env.getProperty("app.speech-recordings.storage-type")).isEqualTo("LOCAL");
        assertThat(env.getProperty("app.speech-recordings.storage-path")).isEqualTo("D:/MeetingApplication/Recordings");

        System.setProperty("RECORDING_LOCAL_PATH", "D:\\Other\\Recordings");
        try {
            assertThat(env.getProperty("app.speech-recordings.storage-path")).isEqualTo("D:\\Other\\Recordings");
        } finally {
            System.clearProperty("RECORDING_LOCAL_PATH");
        }
    }
}
