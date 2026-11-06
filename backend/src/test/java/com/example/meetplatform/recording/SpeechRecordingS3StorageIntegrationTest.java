package com.example.meetplatform.recording;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.recording.SpeechClipFlow.Who;
import com.example.meetplatform.recording.service.SpeechClipObjectStore;
import com.example.meetplatform.recording.service.SpeechRecordingService;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @MockitoSpyBean
    SpeechClipObjectStore objects;

    @Autowired
    MeterRegistry meters;

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

    /** The bucket's lifecycle rule removes objects without telling the application. */
    @Test
    void aClipWhoseObjectIsGoneIsReportedAsUnavailableNotAsADeadLink() throws Exception {
        String m = flow.meeting("Lifecycle");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Lata");
        String id = flow.recordClip(guest, 2).get("id").asString();
        flow.requestAccess(id).andExpect(status().isOk());

        s3.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(PREFIX + storagePath(id)).build());

        flow.requestAccess(id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("This recording is no longer available."));
    }

    @Test
    void retentionRemovesOldClipsKeepsTheRowMarkedDeletedAndLeavesRecentOnesAlone() throws Exception {
        String m = flow.meeting("Retention");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Ravi");
        String expired = flow.recordClip(guest, 2).get("id").asString();
        String alreadyGone = flow.recordClip(guest, 2).get("id").asString();
        String recent = flow.recordClip(guest, 2).get("id").asString();
        jdbc.update("update speech_recordings set start_time = now() - interval '91 days' where id in (?, ?)",
                UUID.fromString(expired), UUID.fromString(alreadyGone));
        // The lifecycle rule got to this one first.
        s3.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(PREFIX + storagePath(alreadyGone)).build());

        // At least these two: the test database is shared with other test classes.
        assertThat(service.deleteExpired().deleted()).isGreaterThanOrEqualTo(2);

        assertThat(service.deleteExpired().deleted()).isZero(); // nothing left to do: rows are consistent now
        assertThat(deletedAt(expired)).isNotNull();
        assertThat(deletedAt(alreadyGone)).isNotNull();
        assertThat(deletedAt(recent)).isNull();
        assertThatThrownBy(() -> s3.statObject(StatObjectArgs.builder().bucket(BUCKET).object(PREFIX + storagePath(expired)).build()))
                .isInstanceOf(ErrorResponseException.class);
        assertThat(s3.statObject(StatObjectArgs.builder().bucket(BUCKET).object(PREFIX + storagePath(recent)).build()).size())
                .isPositive();
        // A deleted clip is no longer listed or played; the row stays as the record.
        flow.requestAccess(expired).andExpect(status().isNotFound());
        flow.requestAccess(recent).andExpect(status().isOk());
    }

    @Test
    void whenStorageIsDownRetentionChangesNothingAndTriesAgainOnTheNextRun() throws Exception {
        String m = flow.meeting("Retention outage");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Tara");
        String id = flow.recordClip(guest, 2).get("id").asString();
        jdbc.update("update speech_recordings set start_time = now() - interval '91 days' where id = ?", UUID.fromString(id));

        removeBucket();
        assertThat(service.deleteExpired().deleted()).isZero();
        assertThat(deletedAt(id)).isNull();

        s3.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        assertThat(service.deleteExpired().deleted()).isPositive();
        assertThat(deletedAt(id)).isNotNull();
    }

    @Test
    void aClipThatKeepsFailingDoesNotHoldUpTheClipsBehindIt() throws Exception {
        String m = flow.meeting("Retention starvation");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Stuck");
        String template = flow.recordClip(guest, 2).get("id").asString();
        String later = flow.recordClip(guest, 2).get("id").asString();
        // More failing clips than one batch holds, all older than the clip that can be removed.
        bulk(template, "stuck-", 60, "120 days");
        jdbc.update("update speech_recordings set start_time = now() - interval '91 days' where id = ?", UUID.fromString(later));
        jdbc.update("update speech_recordings set start_time = now() - interval '91 days' where id = ?", UUID.fromString(template));
        doThrow(new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Access Denied")).when(objects).delete(startsWith("stuck-"));

        SpeechRecordingService.RetentionRun run = service.deleteExpired();

        assertThat(run.failed()).isEqualTo(60);
        assertThat(run.deleted()).isGreaterThanOrEqualTo(2);
        assertThat(run.skipped()).isZero();
        assertThat(deletedAt(later)).isNotNull();
        assertThat(deletedAt(template)).isNotNull();
        assertThat(undeletedLike("stuck-")).as("storage errors never mark a row deleted").isEqualTo(60);
    }

    @Test
    void failedClipsAreTriedAgainOnTheNextRunAndRecoverWhenStorageDoes() throws Exception {
        String m = flow.meeting("Retention recovery");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Flaky");
        String template = flow.recordClip(guest, 2).get("id").asString();
        bulk(template, "flaky-", 5, "95 days");
        doThrow(new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Service Unavailable")).when(objects).delete(startsWith("flaky-"));

        assertThat(service.deleteExpired().failed()).isEqualTo(5);
        assertThat(service.deleteExpired().failed()).as("still eligible on the next run").isEqualTo(5);
        assertThat(undeletedLike("flaky-")).isEqualTo(5);

        doCallRealMethod().when(objects).delete(startsWith("flaky-"));
        SpeechRecordingService.RetentionRun recovered = service.deleteExpired();

        assertThat(recovered.failed()).isZero();
        assertThat(recovered.deleted()).isGreaterThanOrEqualTo(5);
        assertThat(undeletedLike("flaky-")).isZero();
        assertThat(service.deleteExpired().deleted()).as("nothing left to do").isZero();
    }

    @Test
    void moreClipsThanOneBatchAreAllRemovedInOneRun() throws Exception {
        String m = flow.meeting("Retention backlog");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Backlog");
        String template = flow.recordClip(guest, 2).get("id").asString();
        bulk(template, "backlog-", 130, "100 days");

        assertThat(service.deleteExpired().deleted()).isGreaterThanOrEqualTo(130);

        assertThat(undeletedLike("backlog-")).isZero();
    }

    @Test
    void aRunThatReachesItsTimeLimitStopsCleanlyAndTheNextRunFinishes() throws Exception {
        String m = flow.meeting("Retention limit");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Slow");
        String template = flow.recordClip(guest, 2).get("id").asString();
        bulk(template, "slow-", 7, "100 days");

        Object limit = ReflectionTestUtils.getField(service, "retentionRunLimit");
        ReflectionTestUtils.setField(service, "retentionRunLimit", Duration.ofNanos(-1));
        try {
            SpeechRecordingService.RetentionRun run = service.deleteExpired();
            assertThat(run.deleted()).isZero();
            assertThat(run.failed()).isZero();
            assertThat(run.skipped()).isGreaterThanOrEqualTo(7);
        } finally {
            ReflectionTestUtils.setField(service, "retentionRunLimit", limit);
        }
        assertThat(undeletedLike("slow-")).isEqualTo(7);

        assertThat(service.deleteExpired().deleted()).isGreaterThanOrEqualTo(7);
        assertThat(undeletedLike("slow-")).isZero();
    }

    @Test
    void retentionCountsAreExposedForMonitoring() throws Exception {
        String m = flow.meeting("Retention metrics");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Metrics");
        String template = flow.recordClip(guest, 2).get("id").asString();
        bulk(template, "metric-bad-", 3, "100 days");
        doThrow(new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Access Denied")).when(objects).delete(startsWith("metric-bad-"));
        double deleted = retentionCount("deleted");
        double failed = retentionCount("failed");

        SpeechRecordingService.RetentionRun run = service.deleteExpired();

        assertThat(retentionCount("deleted") - deleted).isEqualTo(run.deleted());
        assertThat(retentionCount("failed") - failed).isEqualTo(3);
        doCallRealMethod().when(objects).delete(startsWith("metric-bad-"));
        service.deleteExpired();
    }

    @Test
    void retentionOnlyEverTouchesSpeakingClipsNeverSharedFilesOrRecordings() throws Exception {
        String m = flow.meeting("Retention scope");
        flow.joinAsHost(m);
        Who guest = flow.joinAsGuest(m, "Scope");
        String id = flow.recordClip(guest, 2).get("id").asString();
        jdbc.update("update speech_recordings set start_time = now() - interval '400 days' where id = ?", UUID.fromString(id));

        service.deleteExpired();

        assertThat(deletedAt(id)).isNotNull();
        verify(storage, never()).delete(anyString(), anyString());
    }

    // ------------------------------------------------------------------ helpers

    /** Copies of a clip that are past retention, with paths that say which test they belong to. */
    private void bulk(String templateId, String pathPrefix, int count, String age) {
        String columns = jdbc.queryForObject("""
                select string_agg(quote_ident(column_name), ',') from information_schema.columns
                where table_name = 'speech_recordings' and column_name not in ('id', 'storage_path', 'start_time', 'file_name')
                """, String.class);
        jdbc.update("insert into speech_recordings (id, storage_path, start_time, file_name, " + columns + ") "
                + "select gen_random_uuid(), ? || g || '.webm', now() - interval '" + age + "' - (g || ' seconds')::interval, "
                + "? || g || '.webm', " + columns + " from speech_recordings, generate_series(1, ?) g where id = ?",
                pathPrefix, pathPrefix, count, UUID.fromString(templateId));
    }

    private long undeletedLike(String pathPrefix) {
        return jdbc.queryForObject("select count(*) from speech_recordings where storage_path like ? and deleted_at is null",
                Long.class, pathPrefix + "%");
    }

    private double retentionCount(String result) {
        return meters.counter("meet.speech_clips.retention", "result", result).count();
    }

    private java.sql.Timestamp deletedAt(String clipId) {
        return jdbc.queryForObject("select deleted_at from speech_recordings where id = ?", java.sql.Timestamp.class,
                UUID.fromString(clipId));
    }

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
