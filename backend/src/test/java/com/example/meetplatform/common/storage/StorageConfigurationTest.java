package com.example.meetplatform.common.storage;

import com.example.meetplatform.common.config.AppProperties;
import io.minio.credentials.IamAwsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Storage configuration mistakes stop the application with a clear message instead of failing on the first upload. */
class StorageConfigurationTest {

    private static AppProperties.Storage storage(String type, String recordings, String files, String root) {
        return new AppProperties.Storage("", "", "", "", "ap-south-1", recordings, files, Duration.ofMinutes(15), type, root);
    }

    @Test
    void anUnknownProviderIsRefusedAndKnownOnesAreAcceptedInAnyCase() {
        assertThatThrownBy(() -> storage("gcs", "r", "f", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("STORAGE_PROVIDER");
        assertThatThrownBy(() -> storage(null, "r", "f", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(storage("LOCAL", "r", "f", null).type()).isEqualTo("LOCAL");
        assertThat(storage("S3", "r", "f", null).type()).isEqualTo("S3");
    }

    @Test
    void awsNeedsARegionAndUsesTheIamRoleWhenNoKeysAreSet() {
        assertThatThrownBy(() -> new S3Operations("", "", "", "", "", "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("region");
        assertThat(S3Operations.credentials("", "")).isInstanceOf(IamAwsProvider.class);
        assertThat(S3Operations.credentials(null, null)).isInstanceOf(IamAwsProvider.class);
        // No keys and no endpoint: AWS with the IAM role. Building the client needs no network and no credentials.
        assertThatCode(() -> new S3Operations("", "", "ap-south-1", "", "", "x")).doesNotThrowAnyException();
    }

    @Test
    void anS3CompatibleServerNeedsKeysBecauseAnIamRoleOnlyWorksOnAws() {
        assertThatThrownBy(() -> new S3Operations("http://minio:9000", "", "us-east-1", "", "", "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("IAM role only works with AWS");
    }

    @Test
    void localStorageAcceptsOneBucketNameForBothAreas(@TempDir Path root) {
        AppProperties props = new AppProperties("http://localhost", null, new AppProperties.Security(
                "test-only-secret-0123456789-abcdefghijklmnopqrstuvwxyz", Duration.ofMinutes(15), Duration.ofDays(30),
                false, "meet", null), null, null, storage("local", "one", "one", root.toString()),
                null, null, null, null, null);
        LocalFileObjectStorage local = new LocalFileObjectStorage(props, Clock.systemUTC());
        byte[] body = {1, 2, 3};

        local.put("one", "files/m/f", new ByteArrayInputStream(body), body.length, "text/plain");

        assertThat(local.exists("one", "files/m/f")).isTrue();
        assertThat(local.exists("one", "files/m/gone")).isFalse();
        local.delete("one", "files/m/f");
        assertThat(local.exists("one", "files/m/f")).isFalse();
    }
}
