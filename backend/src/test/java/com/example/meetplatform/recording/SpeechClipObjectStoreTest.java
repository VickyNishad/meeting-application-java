package com.example.meetplatform.recording;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.recording.service.SpeechClipObjectStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Configuration rules of the speaking-clip storage selector (no network involved). */
class SpeechClipObjectStoreTest {

    private static SpeechClipObjectStore store(String type, String region, String bucket, String endpoint) {
        return new SpeechClipObjectStore(type, region, bucket, "", endpoint, "key", "secret-key", new MockEnvironment());
    }

    @Test
    void localIsTheDefaultAndCaseInsensitive() {
        assertThat(store("LOCAL", "", "", "").enabled()).isFalse();
        assertThat(store(" local ", "", "", "").enabled()).isFalse();
        assertThat(store("s3", "eu-west-1", "clips", "").enabled()).isTrue();
    }

    @Test
    void anUnknownTypeOrAnIncompleteS3ConfigurationStopsStartup() {
        assertThatThrownBy(() -> store("DISK", "", "", "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCAL or S3");
        assertThatThrownBy(() -> store("S3", "", "clips", "")).hasMessageContaining("AWS_REGION");
        assertThatThrownBy(() -> store("S3", "eu-west-1", "", "")).hasMessageContaining("AWS_S3_BUCKET");
    }

    @Test
    void anEmptyTypeFollowsTheProfileProductionS3OtherwiseLocal() {
        assertThat(new SpeechClipObjectStore("", "", "", "", "", "", "", new MockEnvironment()).enabled()).isFalse();
        assertThat(new SpeechClipObjectStore(null, "", "", "", "", "", "", new MockEnvironment()).enabled()).isFalse();
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThat(new SpeechClipObjectStore(" ", "eu-west-1", "clips", "", "", "", "", prod).enabled()).isTrue();
        // Production without a bucket does not silently fall back to local disk: startup stops.
        assertThatThrownBy(() -> new SpeechClipObjectStore("", "", "", "", "", "", "", prod))
                .hasMessageContaining("AWS_S3_BUCKET");
        // An explicit LOCAL is refused in production: clips would stay on the server and never expire.
        assertThatThrownBy(() -> new SpeechClipObjectStore("LOCAL", "", "", "", "", "", "", prod))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RECORDING_STORAGE_TYPE=LOCAL");
    }

    @Test
    void anUnreachableBucketIsReportedAsStorageUnavailable() throws Exception {
        SpeechClipObjectStore s = store("S3", "us-east-1", "clips", "http://127.0.0.1:1");
        Path file = Files.createTempFile("clip", ".webm");
        try {
            assertThatThrownBy(() -> s.upload("01-01-2026/M/P/010126000000_abcdef.webm", file, "audio/webm"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void theProductionProfileDefaultsToS3() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yml"))
                .forEach(env.getPropertySources()::addLast);
        assertThat(env.getProperty("app.speech-recordings.storage-type")).isEqualTo("S3");
    }
}
