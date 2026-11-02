package com.example.meetplatform.recording;

import com.example.meetplatform.recording.SpeechClipFlow.Who;
import com.example.meetplatform.recording.service.SpeechRecordingService;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RECORDING_STORAGE_TYPE=S3 against a real S3-compatible server (MinIO in a container, standing in for AWS S3):
 * the existing clip flow is unchanged, finished clips are uploaded under the same folder layout before they are
 * marked COMPLETED/INCOMPLETE, upload failures leave the clip RECORDING and are retried, playback goes through a
 * presigned URL of the private bucket.
 */
@EnabledIf("dockerAvailable")
class SpeechRecordingS3StorageIntegrationTest extends AbstractIntegrationTest {

    private static final String BUCKET = "speech-clips-it";
    private static final String PREFIX = "speech-recordings/";
    private static final String ACCESS = "it-access-key";
    private static final String SECRET = "it-secret-key-123";
    private static final Path STAGING;
    private static GenericContainer<?> minio;

    static {
        try {
            STAGING = Files.createTempDirectory("speech-clips-staging-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry registry) {
        if (minio == null) {
            minio = new GenericContainer<>("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
                    .withCommand("server", "/data")
                    .withEnv("MINIO_ROOT_USER", ACCESS)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET)
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
            minio.start();
        }
        registry.add("app.speech-recordings.storage-type", () -> "S3");
        registry.add("app.speech-recordings.storage-path", STAGING::toString);
        registry.add("app.speech-recordings.s3.region", () -> "us-east-1");
        registry.add("app.speech-recordings.s3.bucket", () -> BUCKET);
        registry.add("app.speech-recordings.s3.prefix", () -> "/speech-recordings/");
        registry.add("app.speech-recordings.s3.endpoint", SpeechRecordingS3StorageIntegrationTest::endpoint);
        registry.add("app.speech-recordings.s3.access-key", () -> ACCESS);
        registry.add("app.speech-recordings.s3.secret-key", () -> SECRET);
    }

    private static String endpoint() {
        return "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    }

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SpeechRecordingService service;

    private SpeechClipFlow flow;
    private MinioClient s3;

    @BeforeEach
    void setUp() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        flow = new SpeechClipFlow(mvc, json, media, users.create(mvc, "S3 Clip Host"));
        s3 = MinioClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET).region("us-east-1").build();
        if (!s3.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            s3.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void finishedClipIsUploadedUnderTheSameFolderLayoutBeforeItIsCompletedAndStagingIsCleared() throws Exception {
        String m = flow.meeting("Board: Q4 plan");
        flow.joinAsHost(m);
        Who vicky = flow.joinAsGuest(m, "Vicky Kumar");
        JsonNode clip = flow.recordClip(vicky, 3);

        assertThat(clip.get("status").asString()).isEqualTo("COMPLETED");
        String path = storagePath(clip.get("id").asString());
        String today = jdbc.queryForObject("select to_char(now() at time zone 'UTC', 'DD-MM-YYYY')", String.class);
        // Existing hierarchy and file name convention, unchanged.
        assertThat(path).matches(today + "/Board_Q4_plan/Vicky_Kumar/\\d{12}_[0-9a-f]{6}\\.webm");
        assertThat(path).endsWith("/" + clip.get("fileName").asString());

        StatObjectResponse object = s3.statObject(StatObjectArgs.builder().bucket(BUCKET).object(PREFIX + path).build());
        assertThat(object.size()).isEqualTo(clip.get("fileSize").asLong());
        assertThat(object.contentType()).startsWith("audio/webm");
        // The staged copy is gone once COMPLETED was committed; nothing partial is left behind.
        assertThat(STAGING.resolve(path)).doesNotExist();
        assertThat(STAGING.resolve(path + ".part")).doesNotExist();
    }

    @Test
    void uploadFailureLeavesTheClipRecordingWithItsFileAndARetryCompletesIt() throws Exception {
        String m = flow.meeting("Outage drill");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Asha");
        String id = flow.recordUnfinished(guest, 2);

        removeBucket();
        flow.finish(guest, id, 2).andExpect(status().isServiceUnavailable());
        String path = storagePath(id);
        assertThat(clipStatus(id)).isEqualTo("RECORDING");
        assertThat(STAGING.resolve(path)).exists(); // finalized and verified locally, kept for the retry

        s3.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        JsonNode done = flow.read(flow.finish(guest, id, 2).andExpect(status().isOk()));
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(s3.statObject(StatObjectArgs.builder().bucket(BUCKET).object(PREFIX + path).build()).size())
                .isEqualTo(done.get("fileSize").asLong());
        assertThat(STAGING.resolve(path)).doesNotExist();
    }

    @Test
    void recoveryUploadsAbandonedClipsAsIncompleteAndRetriesWhileStorageIsDown() throws Exception {
        String m = flow.meeting("Dropped call");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Rahul");
        String id = flow.recordUnfinished(guest, 2);
        jdbc.update("update speech_recordings set last_chunk_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))), UUID.fromString(id));

        removeBucket();
        assertThat(service.recoverStale(Duration.ofMinutes(2))).isZero();
        assertThat(clipStatus(id)).isEqualTo("RECORDING"); // never INCOMPLETE without its object

        s3.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        assertThat(service.recoverStale(Duration.ofMinutes(2))).isEqualTo(1);
        assertThat(clipStatus(id)).isEqualTo("INCOMPLETE");
        String path = storagePath(id);
        assertThat(s3.statObject(StatObjectArgs.builder().bucket(BUCKET).object(PREFIX + path).build()).size()).isPositive();
        assertThat(STAGING.resolve(path)).doesNotExist();
    }

    @Test
    void playbackAndDownloadRedirectToPresignedUrlsOfThePrivateBucket() throws Exception {
        String m = flow.meeting("Playback");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Meera");
        JsonNode clip = flow.recordClip(guest, 2);
        String id = clip.get("id").asString();
        HttpClient http = HttpClient.newHttpClient();

        // Same dashboard API as before: access-url, then the signed content link (now a redirect).
        MockHttpServletResponse play = mvc.perform(get(flow.accessPath(id, false)))
                .andExpect(status().isFound()).andReturn().getResponse();
        String location = play.getHeader("Location");
        assertThat(location).startsWith(endpoint() + "/" + BUCKET + "/" + PREFIX).contains("X-Amz-Signature=");
        assertThat(play.getHeader("Cache-Control")).contains("no-store");
        HttpResponse<byte[]> inline = http.send(HttpRequest.newBuilder(URI.create(location)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(inline.statusCode()).isEqualTo(200);
        assertThat(inline.body()).hasSize(clip.get("fileSize").asInt()).startsWith(SpeechClipFlow.WEBM_HEAD);
        assertThat(inline.headers().firstValue("Content-Disposition").orElse("")).startsWith("inline");
        assertThat(inline.headers().firstValue("Content-Type").orElse("")).startsWith("audio/webm");

        String downloadLocation = mvc.perform(get(flow.accessPath(id, true)))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        HttpResponse<byte[]> attachment = http.send(HttpRequest.newBuilder(URI.create(downloadLocation)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(attachment.headers().firstValue("Content-Disposition").orElse(""))
                .startsWith("attachment").contains(clip.get("fileName").asString());

        // The bucket is private: the object without a signature is refused.
        String plain = endpoint() + "/" + BUCKET + "/" + PREFIX + storagePath(id);
        assertThat(http.send(HttpRequest.newBuilder(URI.create(plain)).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode()).isEqualTo(403);
        // A tampered link is still rejected by the API before any redirect.
        mvc.perform(get(flow.accessPath(id, false).replaceAll("sig=[^&]+", "sig=AAAA"))).andExpect(status().isForbidden());
    }

    @Test
    void nonLatinMeetingAndParticipantNamesKeepTheirFolderNamesInS3AndPlay() throws Exception {
        String m = flow.meeting("साप्ताहिक बैठक");
        flow.joinAsHost(m);
        Who asha = flow.joinAsGuest(m, "आशा देवी");
        JsonNode clip = flow.recordClip(asha, 2);

        assertThat(clip.get("status").asString()).isEqualTo("COMPLETED");
        String path = storagePath(clip.get("id").asString());
        assertThat(path).contains("/साप्ताहिक_बैठक/आशा_देवी/");
        assertThat(s3.statObject(StatObjectArgs.builder().bucket(BUCKET).object(PREFIX + path).build()).size())
                .isEqualTo(clip.get("fileSize").asLong());

        String location = mvc.perform(get(flow.accessPath(clip.get("id").asString(), true)))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(location)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).startsWith(SpeechClipFlow.WEBM_HEAD);
    }

    // ------------------------------------------------------------------ helpers

    private String storagePath(String clipId) {
        return jdbc.queryForObject("select storage_path from speech_recordings where id = ?", String.class,
                UUID.fromString(clipId));
    }

    private String clipStatus(String clipId) {
        return jdbc.queryForObject("select status from speech_recordings where id = ?", String.class,
                UUID.fromString(clipId));
    }

    /** Simulates an S3 outage for this bucket: emptied and removed (uploads then fail with NoSuchBucket). */
    private void removeBucket() throws Exception {
        for (Result<Item> r : s3.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).build())) {
            s3.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(r.get().objectName()).build());
        }
        s3.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
    }
}
