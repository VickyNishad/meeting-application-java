package com.example.meetplatform.recording.service;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import io.minio.credentials.IamAwsProvider;
import io.minio.credentials.Provider;
import io.minio.credentials.StaticProvider;
import io.minio.http.Method;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Where finished speaking clips are kept, chosen by {@code RECORDING_STORAGE_TYPE}:
 * <ul>
 *   <li>{@code LOCAL} (default): the clip stays where {@link SpeechClipStorage} finalized it, under
 *   {@code RECORDING_LOCAL_PATH}. This class does nothing.</li>
 *   <li>{@code S3}: {@link SpeechClipStorage}'s folder is only a staging area. A finalized, verified clip is uploaded
 *   to a private bucket under the same relative path ({@code DD-MM-YYYY/<Meeting>/<Participant>/<file>}), and is
 *   played through short-lived presigned URLs.</li>
 * </ul>
 * Credentials: {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY} when set, otherwise the server's IAM role
 * (EC2 instance profile, ECS task role or EKS web identity). The bucket is never created or made public here.
 */
@Component
public class SpeechClipObjectStore {

    private static final Logger log = LoggerFactory.getLogger(SpeechClipObjectStore.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(2);

    private final boolean enabled;
    private final String bucket;
    private final String prefix;
    private final MinioClient client;

    public SpeechClipObjectStore(@Value("${app.speech-recordings.storage-type:}") String storageType,
                                 @Value("${app.speech-recordings.s3.region:}") String region,
                                 @Value("${app.speech-recordings.s3.bucket:}") String bucket,
                                 @Value("${app.speech-recordings.s3.prefix:}") String prefix,
                                 @Value("${app.speech-recordings.s3.endpoint:}") String endpoint,
                                 @Value("${app.speech-recordings.s3.access-key:}") String accessKey,
                                 @Value("${app.speech-recordings.s3.secret-key:}") String secretKey,
                                 Environment environment) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        String type = storageType == null ? "" : storageType.strip().toUpperCase(Locale.ROOT);
        if (type.isEmpty()) {
            // Not set (docker-compose passes it through empty): production keeps clips in S3, anything else on disk.
            type = production ? "S3" : "LOCAL";
        }
        if (!type.equals("LOCAL") && !type.equals("S3")) {
            throw new IllegalStateException("RECORDING_STORAGE_TYPE must be LOCAL or S3, not '" + storageType + "'");
        }
        this.enabled = type.equals("S3");
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
        if (!enabled) {
            if (production) {
                log.warn("Production profile with RECORDING_STORAGE_TYPE=LOCAL: speaking clips stay on this server's "
                        + "disk. Set RECORDING_STORAGE_TYPE=S3 to keep them in AWS S3.");
            }
            this.client = null;
            return;
        }
        if (isBlank(region) || isBlank(bucket)) {
            throw new IllegalStateException("RECORDING_STORAGE_TYPE=S3 needs AWS_REGION and AWS_S3_BUCKET");
        }
        Provider credentials = credentials(accessKey, secretKey);
        this.client = MinioClient.builder()
                .endpoint(isBlank(endpoint) ? "https://s3." + region + ".amazonaws.com" : endpoint)
                // Set explicitly so presigning is a pure local computation (no region lookup round-trip).
                .region(region)
                .credentialsProvider(credentials)
                // The upload runs inside the finish transaction: fail fast instead of OkHttp's 5-minute defaults.
                .httpClient(new OkHttpClient.Builder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .writeTimeout(TRANSFER_TIMEOUT)
                        .readTimeout(TRANSFER_TIMEOUT)
                        .build())
                .build();
        log.info("Speaking clips are uploaded to s3://{}/{} ({} credentials)", bucket, this.prefix,
                credentials instanceof StaticProvider ? "access-key" : "IAM role");
    }

    /**
     * Access keys when both are configured (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY), otherwise the server's IAM
     * role: EC2 instance profile (IMDS), ECS task role or EKS web identity. The IAM provider is used on its own: in a
     * ChainedProvider, MinIO's AwsEnvironmentProvider throws (not ProviderException) when the keys are unset or empty,
     * which would stop the chain before the IAM role is ever tried.
     */
    static Provider credentials(String accessKey, String secretKey) {
        if (!isBlank(accessKey) && !isBlank(secretKey)) {
            return new StaticProvider(accessKey, secretKey, null);
        }
        return new IamAwsProvider(null, null);
    }

    /** True when finished clips go to S3 (the local folder is then staging only). */
    public boolean enabled() {
        return enabled;
    }

    /** Startup check only: a missing or unreachable bucket is reported, never created (it must stay private). */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyBucket() {
        if (!enabled) {
            return;
        }
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                log.error("Speaking-clip bucket {} does not exist; clips stay RECORDING until it does", bucket);
            }
        } catch (Exception e) {
            log.error("Speaking-clip bucket {} is not reachable at startup: {}", bucket, e.getMessage());
        }
    }

    /**
     * Uploads a finalized clip to its key. Uploading the same clip again (a retry after a rolled-back transaction)
     * just writes the same object again.
     */
    public void upload(String relativePath, Path file, String contentType) {
        try {
            client.uploadObject(UploadObjectArgs.builder()
                    .bucket(bucket).object(key(relativePath)).filename(file.toString()).contentType(contentType).build());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Recording storage is unavailable.", e);
        }
    }

    /** A presigned GET for the clip: inline for playback, attachment for download, with the clip's own file name. */
    public String presignGet(String relativePath, String fileName, String contentType, boolean download, Duration ttl) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket).object(key(relativePath)).expiry((int) ttl.toSeconds())
                    .extraQueryParams(Map.of(
                            "response-content-disposition", (download ? "attachment" : "inline") + "; filename*=UTF-8''" + encoded,
                            "response-content-type", contentType))
                    .build());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Recording storage is unavailable.", e);
        }
    }

    /** The object key: the optional prefix plus the clip's relative path, always with '/' separators. */
    String key(String relativePath) {
        return prefix + relativePath.replace('\\', '/');
    }

    private static String normalizePrefix(String prefix) {
        if (isBlank(prefix)) {
            return "";
        }
        String p = prefix.strip().replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
        return p.isEmpty() ? "" : p + "/";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
