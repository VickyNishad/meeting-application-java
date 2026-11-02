package com.example.meetplatform.recording.service;

import io.minio.credentials.Credentials;
import io.minio.credentials.IamAwsProvider;
import io.minio.credentials.Provider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-1: which credentials the S3 client uses. Fetches through the chosen provider, not just its construction
 * (a chain starting with MinIO's AwsEnvironmentProvider used to abort before the IAM role was ever tried).
 */
class SpeechClipCredentialsTest {

    @Test
    void configuredAccessKeysAreUsedAsTheyAre() {
        Credentials c = SpeechClipObjectStore.credentials("AKIAEXAMPLE", "secret-example").fetch();
        assertThat(c.accessKey()).isEqualTo("AKIAEXAMPLE");
        assertThat(c.secretKey()).isEqualTo("secret-example");
    }

    @Test
    void withoutKeysTheIamRoleProviderIsUsedDirectly() {
        for (String[] keys : new String[][] {{null, null}, {"", ""}, {" ", " "}, {"AKIAEXAMPLE", ""}}) {
            Provider p = SpeechClipObjectStore.credentials(keys[0], keys[1]);
            // Not wrapped in a chain: nothing in front of it can throw before the instance profile / task role.
            assertThat(p).isExactlyInstanceOf(IamAwsProvider.class);
        }
    }
}
