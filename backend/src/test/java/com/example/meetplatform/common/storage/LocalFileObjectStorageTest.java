package com.example.meetplatform.common.storage;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileObjectStorageTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @TempDir
    Path root;

    @Test
    void storesUnderBucketFolderAndSignedUrlResolvesToTheFile() throws Exception {
        LocalFileObjectStorage storage = storage(root.toString(), NOW);
        byte[] body = "video".getBytes(StandardCharsets.UTF_8);

        storage.put("meet-recordings", "recordings/m1/r1.mp4", new ByteArrayInputStream(body), body.length, "video/mp4");

        Path stored = root.resolve("meet-recordings/recordings/m1/r1.mp4");
        assertThat(stored).hasBinaryContent(body);
        assertThat(Files.list(stored.getParent())).containsExactly(stored);

        ObjectStorage.PresignedUrl url = storage.presignGet("meet-recordings", "recordings/m1/r1.mp4",
                Duration.ofMinutes(15), "My meeting.mp4");
        assertThat(url.url()).startsWith("http://127.0.0.1:5173/api/v1/storage/local?");
        assertThat(url.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));

        Map<String, String> q = query(url.url());
        assertThat(storage.verifiedPath(q.get("bucket"), q.get("key"), Long.parseLong(q.get("expires")),
                q.get("name"), q.get("sig"))).isEqualTo(stored);
    }

    @Test
    void tamperedOrExpiredLinksAreRefused() {
        LocalFileObjectStorage storage = storage(root.toString(), NOW);
        Map<String, String> q = query(storage.presignGet("meet-files", "files/a.pdf", Duration.ofMinutes(1), null).url());
        long expires = Long.parseLong(q.get("expires"));

        assertThatThrownBy(() -> storage.verifiedPath("meet-files", "files/b.pdf", expires, null, q.get("sig")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.verifiedPath("meet-files", "files/a.pdf", expires + 3600, null, q.get("sig")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.verifiedPath("meet-files", "files/a.pdf", expires, "other.pdf", q.get("sig")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.verifiedPath("meet-files", "files/a.pdf", expires, null, null))
                .isInstanceOf(ApiException.class);

        LocalFileObjectStorage later = storage(root.toString(), NOW.plus(Duration.ofMinutes(2)));
        assertThatThrownBy(() -> later.verifiedPath("meet-files", "files/a.pdf", expires, null, q.get("sig")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void keysCannotEscapeTheirBucketFolder() {
        LocalFileObjectStorage storage = storage(root.toString(), NOW);
        for (String key : List.of("../meet-files/x", "../../outside.txt", "a/../../x", "", " ")) {
            assertThatThrownBy(() -> storage.put("meet-recordings", key, new ByteArrayInputStream(new byte[0]), 0, null))
                    .as(key).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> storage.presignGet("other-bucket", "x", Duration.ofMinutes(1), null))
                .isInstanceOf(ApiException.class);
        assertThat(root.resolve("outside.txt")).doesNotExist();
    }

    @Test
    void deleteRemovesTheFileAndIgnoresMissingOnes() throws Exception {
        LocalFileObjectStorage storage = storage(root.toString(), NOW);
        storage.put("meet-files", "f/1.txt", new ByteArrayInputStream(new byte[]{1}), 1, "text/plain");

        storage.delete("meet-files", "f/1.txt");
        storage.delete("meet-files", "f/1.txt");

        assertThat(root.resolve("meet-files/f/1.txt")).doesNotExist();
    }

    @Test
    void createsBucketFoldersAndRequiresARoot() {
        storage(root.resolve("meetly").toString(), NOW);
        assertThat(root.resolve("meetly/meet-recordings")).isDirectory();
        assertThat(root.resolve("meetly/meet-files")).isDirectory();

        assertThatThrownBy(() -> storage("", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> storage(null, NOW)).isInstanceOf(IllegalStateException.class);
    }

    private static LocalFileObjectStorage storage(String localRoot, Instant now) {
        AppProperties.Storage storage = new AppProperties.Storage("http://unused", "http://unused", "k", "s",
                "us-east-1", "meet-recordings", "meet-files", Duration.ofMinutes(15), "local", localRoot);
        AppProperties.Security security = new AppProperties.Security(
                "test-only-secret-0123456789-abcdefghijklmnopqrstuvwxyz", Duration.ofMinutes(15), Duration.ofDays(30),
                false, "meet", null);
        AppProperties props = new AppProperties("http://127.0.0.1:5173/", null, security, null, null, storage,
                null, null, null, null, null);
        return new LocalFileObjectStorage(props, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Map<String, String> query(String url) {
        return UriComponentsBuilder.fromUriString(url).build(true).getQueryParams().toSingleValueMap().entrySet()
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> java.net.URLDecoder.decode(e.getValue(), StandardCharsets.UTF_8)));
    }
}
