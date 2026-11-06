package com.example.meetplatform.common.storage;

import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "dev" profile switches object storage to the local disk; signed links are served without a login and
 * support range requests (video seeking). The real D: drive is replaced by a temp folder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class DevProfileLocalStorageIntegrationTest {

    private static final Path ROOT = tempRoot();

    @MockitoBean
    MediaService media;

    @Autowired
    ObjectStorage storage;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.Connection db = TestDatabase.get();
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("app.storage.local-root", ROOT::toString);
    }

    @Test
    void devProfileStoresRecordingsOnDiskAndServesThemThroughSignedLinks() throws Exception {
        assertThat(storage).isInstanceOf(LocalFileObjectStorage.class);
        byte[] body = "0123456789".getBytes(StandardCharsets.UTF_8);
        storage.put("meet-recordings", "recordings/m/r.mp4", new ByteArrayInputStream(body), body.length, "video/mp4");
        assertThat(ROOT.resolve("meeting-recordings/recordings/m/r.mp4")).hasBinaryContent(body);

        String playback = path(storage.presignGet("meet-recordings", "recordings/m/r.mp4", Duration.ofMinutes(5), null));
        mvc.perform(get(URI.create(playback)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(content().bytes(body));
        mvc.perform(get(URI.create(playback)).header(HttpHeaders.RANGE, "bytes=2-4"))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes("234".getBytes(StandardCharsets.UTF_8)));

        String download = path(storage.presignGet("meet-recordings", "recordings/m/r.mp4", Duration.ofMinutes(5),
                "Weekly sync_2026-10-29.mp4"));
        mvc.perform(get(URI.create(download)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment;")));
    }

    @Test
    void forgedOrBrokenLinksAreRefused() throws Exception {
        storage.put("meet-files", "files/a.txt", new ByteArrayInputStream(new byte[]{1}), 1, "text/plain");
        String link = path(storage.presignGet("meet-files", "files/a.txt", Duration.ofMinutes(5), null));

        mvc.perform(get(URI.create(link.replace("files%2Fa.txt", "files%2Fb.txt")))).andExpect(status().isForbidden());
        mvc.perform(get(URI.create(link.replaceAll("sig=[^&]+", "sig=AAAA")))).andExpect(status().isForbidden());
        mvc.perform(get(URI.create(link.replaceAll("&sig=[^&]+", "")))).andExpect(status().is4xxClientError());

        storage.delete("meet-files", "files/a.txt");
        mvc.perform(get(URI.create(link))).andExpect(status().isNotFound());
    }

    private static String path(ObjectStorage.PresignedUrl url) {
        return url.url().substring("http://localhost:5173".length());
    }

    private static Path tempRoot() {
        try {
            return Files.createTempDirectory("meetly-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
