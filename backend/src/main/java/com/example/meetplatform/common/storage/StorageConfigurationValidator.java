package com.example.meetplatform.common.storage;

import com.example.meetplatform.common.config.AppProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

/**
 * Checks the storage settings as they finally resolved, so a mistake stops startup with a clear message rather than
 * surfacing as a broken download link or a file in the wrong place. Speaking clips are checked in
 * {@code SpeechClipObjectStore}, which works out their mode.
 */
@Component
public class StorageConfigurationValidator {

    /** Names that only mean something to the server itself, or inside the Docker network. */
    private static final Set<String> INTERNAL_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]", "minio");

    public StorageConfigurationValidator(AppProperties properties, Environment environment,
                                         @Value("${STORAGE_PROVIDER:}") String requestedProvider,
                                         @Value("${app.speech-recordings.s3.bucket:}") String sharedBucket) {
        validate(properties.storage(), environment.acceptsProfiles(Profiles.of("prod")), requestedProvider, sharedBucket);
    }

    static void validate(AppProperties.Storage storage, boolean production, String requestedProvider, String sharedBucket) {
        boolean s3 = "s3".equalsIgnoreCase(storage.type());
        if (production && (!s3 || "local".equalsIgnoreCase(strip(requestedProvider)))) {
            throw new IllegalStateException("Local storage cannot be used with the prod profile, which always stores "
                    + "files in S3. Set STORAGE_PROVIDER=s3, or leave SPRING_PROFILES_ACTIVE empty for local development.");
        }
        if (!s3) {
            return;
        }
        boolean aws = S3Operations.isBlank(storage.endpoint());
        if (aws) {
            requireNoPublicEndpoint(storage);
            requireOneBucket(storage, sharedBucket);
        } else if (production) {
            requireReachablePublicEndpoint(storage);
        }
    }

    private static void requireNoPublicEndpoint(AppProperties.Storage storage) {
        if (!S3Operations.isBlank(storage.publicEndpoint())) {
            throw new IllegalStateException("S3_PUBLIC_ENDPOINT (" + storage.publicEndpoint() + ") must be empty with AWS "
                    + "S3: signed links would point there instead of at the bucket. It is only for an S3-compatible "
                    + "server, which also needs S3_ENDPOINT.");
        }
    }

    /** The bucket has one policy and one lifecycle rule: an old value left in .env would send data somewhere else. */
    private static void requireOneBucket(AppProperties.Storage storage, String sharedBucket) {
        if (S3Operations.isBlank(sharedBucket)) {
            return;
        }
        if (!sharedBucket.equals(storage.filesBucket()) || !sharedBucket.equals(storage.recordingsBucket())) {
            throw new IllegalStateException("AWS S3 uses one bucket, but AWS_S3_BUCKET is " + sharedBucket + ", files "
                    + "use " + storage.filesBucket() + " and recordings use " + storage.recordingsBucket()
                    + ". Clear S3_FILES_BUCKET and S3_RECORDINGS_BUCKET.");
        }
    }

    private static void requireReachablePublicEndpoint(AppProperties.Storage storage) {
        String endpoint = storage.publicEndpoint();
        if (S3Operations.isBlank(endpoint)) {
            throw new IllegalStateException("An S3-compatible server in production needs S3_PUBLIC_ENDPOINT: without it, "
                    + "signed links point at " + storage.endpoint() + ", which browsers cannot reach.");
        }
        String host = host(endpoint);
        if (host == null || INTERNAL_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException("S3_PUBLIC_ENDPOINT (" + endpoint + ") must be an address browsers can "
                    + "reach, not a local or internal one.");
        }
    }

    private static String host(String endpoint) {
        try {
            return URI.create(strip(endpoint)).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
