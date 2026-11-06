package com.example.meetplatform.common.storage;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.recording.service.SpeechClipObjectStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real application yml files with the variables a deployment sets, resolved as at startup: real precedence between
 * STORAGE_PROVIDER, RECORDING_STORAGE_TYPE and the profile files, then the same checks the application runs.
 */
class StorageStartupValidationTest {

    /** What Compose passes on AWS: empty values are real empty strings here, as they are in the container. */
    private static final Map<String, Object> AWS = Map.of("STORAGE_PROVIDER", "s3", "S3_ENDPOINT", "",
            "S3_PUBLIC_ENDPOINT", "", "AWS_REGION", "ap-south-1", "AWS_S3_BUCKET", "meetly-prod");

    private static Map<String, Object> aws(String... overrides) {
        Map<String, Object> vars = new HashMap<>(AWS);
        for (int i = 0; i < overrides.length; i += 2) {
            vars.put(overrides[i], overrides[i + 1]);
        }
        return vars;
    }

    private record Started(AppProperties.Storage storage, boolean clipsInS3) {
    }

    /** Throws what startup would throw. */
    private static Started start(Map<String, Object> vars, String... profiles) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env", vars));
        for (String profile : profiles) {
            new YamlPropertySourceLoader().load(profile, new ClassPathResource("application-" + profile + ".yml"))
                    .forEach(env.getPropertySources()::addLast);
        }
        new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        env.setActiveProfiles(profiles);
        ConfigurationPropertySources.attach(env);

        AppProperties.Storage storage = Binder.get(env).bind("app.storage", AppProperties.Storage.class).get();
        String requested = String.valueOf(vars.getOrDefault("STORAGE_PROVIDER", ""));
        StorageConfigurationValidator.validate(storage, env.acceptsProfiles(Profiles.of("prod")),
                requested, env.getProperty("app.speech-recordings.s3.bucket", ""));
        SpeechClipObjectStore clips = new SpeechClipObjectStore(env.getProperty("app.speech-recordings.storage-type"),
                env.getProperty("app.speech-recordings.s3.region", ""), env.getProperty("app.speech-recordings.s3.bucket", ""),
                env.getProperty("app.speech-recordings.s3.prefix", ""), env.getProperty("app.speech-recordings.s3.endpoint", ""),
                env.getProperty("app.speech-recordings.s3.access-key", ""),
                env.getProperty("app.speech-recordings.s3.secret-key", ""), env);
        return new Started(storage, clips.enabled());
    }

    @Test
    void productionWithS3ForFilesAndClipsStarts() throws Exception {
        Started s = start(aws(), "prod");

        assertThat(s.storage().type()).isEqualTo("s3");
        assertThat(s.storage().filesBucket()).isEqualTo("meetly-prod");
        assertThat(s.storage().recordingsBucket()).isEqualTo("meetly-prod");
        assertThat(s.storage().accessKey()).isEmpty();
        assertThat(s.storage().publicEndpoint()).isEmpty();
        assertThat(s.clipsInS3()).isTrue();
    }

    @Test
    void productionRefusesLocalSharedStorage() {
        assertThatThrownBy(() -> start(aws("STORAGE_PROVIDER", "local"), "prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod profile");
    }

    @Test
    void productionRefusesLocalStorageRequestedThroughTheOldVariable() {
        // Relaxed binding: APP_STORAGE_TYPE reaches app.storage.type above the prod file's fixed value.
        Map<String, Object> vars = aws("APP_STORAGE_TYPE", "local");
        vars.remove("STORAGE_PROVIDER");

        assertThatThrownBy(() -> start(vars, "prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod profile");
    }

    @Test
    void productionRefusesLocalSpeakingClipsEvenWithS3Files() {
        assertThatThrownBy(() -> start(aws("RECORDING_STORAGE_TYPE", "LOCAL"), "prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RECORDING_STORAGE_TYPE=LOCAL");
    }

    @Test
    void productionClipTypeIsCaseInsensitiveSoNoSpellingSlipsThrough() {
        assertThatThrownBy(() -> start(aws("RECORDING_STORAGE_TYPE", " local "), "prod"))
                .hasMessageContaining("RECORDING_STORAGE_TYPE=LOCAL");
    }

    @Test
    void developmentWithLocalFilesAndLocalClipsStarts() throws Exception {
        Started s = start(Map.of("STORAGE_PROVIDER", "local", "RECORDING_STORAGE_TYPE", "LOCAL",
                "STORAGE_LOCAL_BASE_PATH", "./data"), "dev");

        assertThat(s.storage().type()).isEqualTo("local");
        assertThat(s.clipsInS3()).isFalse();
    }

    @Test
    void developmentDefaultsToLocalWithNoCloudSettingsAtAll() throws Exception {
        Started s = start(Map.of(), "dev");

        assertThat(s.storage().type()).isEqualTo("local");
        assertThat(s.clipsInS3()).isFalse();
    }

    @Test
    void localStorageIgnoresLeftoverS3Values() throws Exception {
        // This PC's own .env: old MinIO values next to STORAGE_PROVIDER unset, which Compose passes as local.
        Started s = start(Map.of("STORAGE_PROVIDER", "local", "S3_PUBLIC_ENDPOINT", "http://localhost:9000",
                "S3_RECORDINGS_BUCKET", "meet-recordings", "RECORDING_STORAGE_TYPE", "LOCAL"));

        assertThat(s.storage().type()).isEqualTo("local");
    }

    @Test
    void developmentWithMinioKeepsWorkingLikeBefore() throws Exception {
        Started s = start(Map.of("STORAGE_PROVIDER", "s3", "S3_ENDPOINT", "http://minio:9000",
                "S3_PUBLIC_ENDPOINT", "http://localhost:9000", "S3_ACCESS_KEY", "key", "S3_SECRET_KEY", "secret-value",
                "RECORDING_STORAGE_TYPE", "LOCAL"));

        assertThat(s.storage().type()).isEqualTo("s3");
        assertThat(s.storage().publicEndpoint()).isEqualTo("http://localhost:9000");
        assertThat(s.clipsInS3()).isFalse();
    }

    @Test
    void developmentClipsFollowStorageProviderWhenTheirOwnSettingIsEmpty() throws Exception {
        assertThat(start(aws()).clipsInS3()).isTrue();
    }

    @Test
    void recordingStorageTypeWinsOverStorageProvider() throws Exception {
        assertThat(start(aws("RECORDING_STORAGE_TYPE", "LOCAL")).clipsInS3()).isFalse();
    }

    @Test
    void anUnsetPublicEndpointNoLongerDefaultsToLocalhost() throws Exception {
        Map<String, Object> vars = aws();
        vars.remove("S3_PUBLIC_ENDPOINT");

        assertThat(start(vars, "prod").storage().publicEndpoint()).isEmpty();
    }

    @Test
    void aPublicEndpointOnAwsIsRefusedInEveryProfile() {
        for (String value : new String[] {"http://localhost:9000", "https://s3.meet.example.com"}) {
            assertThatThrownBy(() -> start(aws("S3_PUBLIC_ENDPOINT", value), "prod"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("S3_PUBLIC_ENDPOINT");
            assertThatThrownBy(() -> start(aws("S3_PUBLIC_ENDPOINT", value)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("S3_PUBLIC_ENDPOINT");
        }
    }

    @Test
    void aLeftoverRecordingsOrFilesBucketIsRefusedOnAws() {
        assertThatThrownBy(() -> start(aws("S3_RECORDINGS_BUCKET", "meet-recordings"), "prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("S3_RECORDINGS_BUCKET");
        assertThatThrownBy(() -> start(aws("S3_FILES_BUCKET", "meet-files"), "prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("meet-files");
    }

    @Test
    void theSameBucketNamedExplicitlyIsFine() {
        assertThatCode(() -> start(aws("S3_RECORDINGS_BUCKET", "meetly-prod", "S3_FILES_BUCKET", "meetly-prod"), "prod"))
                .doesNotThrowAnyException();
    }

    private static AppProperties.Storage compatible(String publicEndpoint) {
        return new AppProperties.Storage("http://minio:9000", publicEndpoint, "key", "secret", "us-east-1", "r", "f",
                Duration.ofMinutes(15), "s3", "");
    }

    @Test
    void anS3CompatibleServerInProductionNeedsAnAddressBrowsersCanReach() {
        for (String bad : new String[] {"", "http://localhost:9000", "http://127.0.0.1:9000", "http://minio:9000", "not a url"}) {
            assertThatThrownBy(() -> StorageConfigurationValidator.validate(compatible(bad), true, "s3", ""))
                    .as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("S3_PUBLIC_ENDPOINT");
        }
        assertThatCode(() -> StorageConfigurationValidator.validate(compatible("https://s3.meet.example.com"), true, "s3", ""))
                .doesNotThrowAnyException();
    }

    @Test
    void aFailedCheckStopsTheApplicationContext() {
        AppProperties.Storage local = new AppProperties.Storage("", "", "", "", "us-east-1", "r", "f",
                Duration.ofMinutes(15), "local", "");
        AppProperties properties = new AppProperties("http://localhost", null, null, null, null, local,
                null, null, null, null, null);
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withBean(AppProperties.class, () -> properties)
                .withUserConfiguration(StorageConfigurationValidator.class)
                .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> properties)
                .withUserConfiguration(StorageConfigurationValidator.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theExampleEnvironmentFileHoldsNoLocalhostStorageLinkOrKey() throws Exception {
        String example = java.nio.file.Files.readString(java.nio.file.Path.of("..", ".env.example"));

        assertThat(example).containsPattern("(?m)^S3_PUBLIC_ENDPOINT=$");
        assertThat(example).containsPattern("(?m)^S3_RECORDINGS_BUCKET=$");
        assertThat(example).containsPattern("(?m)^AWS_ACCESS_KEY_ID=$");
        assertThat(example).containsPattern("(?m)^AWS_SECRET_ACCESS_KEY=$");
    }

}
