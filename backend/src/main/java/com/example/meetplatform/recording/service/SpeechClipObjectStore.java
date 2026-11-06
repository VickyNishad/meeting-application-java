package com.example.meetplatform.recording.service;

import com.example.meetplatform.common.storage.S3Operations;
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

    private final boolean enabled;
    private final String bucket;
    private final String prefix;
    private final S3Operations s3;

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
        if (production && type.equals("LOCAL")) {
            throw new IllegalStateException("RECORDING_STORAGE_TYPE=LOCAL cannot be used with the prod profile: speaking "
                    + "clips would stay on this server's disk and never expire. Clear RECORDING_STORAGE_TYPE, or leave "
                    + "SPRING_PROFILES_ACTIVE empty for local development.");
        }
        this.enabled = type.equals("S3");
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
        if (!enabled) {
            this.s3 = null;
            return;
        }
        if (isBlank(region) || isBlank(bucket)) {
            throw new IllegalStateException("RECORDING_STORAGE_TYPE=S3 needs AWS_REGION and AWS_S3_BUCKET");
        }
        this.s3 = new S3Operations(endpoint, "", region, accessKey, secretKey, "Recording storage is unavailable.");
        log.info("Speaking clips are uploaded to s3://{}/{} ({} credentials)", bucket, this.prefix,
                isBlank(accessKey) || isBlank(secretKey) ? "IAM role" : "access-key");
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
            if (!s3.bucketExists(bucket)) {
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
        s3.upload(bucket, key(relativePath), file, contentType);
    }

    /** False when the object is gone, e.g. expired by the bucket's lifecycle rule. */
    public boolean exists(String relativePath) {
        return s3.exists(bucket, key(relativePath));
    }

    /** Removes the clip's object; one already gone (a lifecycle rule got there first) is not an error. */
    public void delete(String relativePath) {
        s3.delete(bucket, key(relativePath));
    }

    /** A presigned GET for the clip: inline for playback, attachment for download, with the clip's own file name. */
    public String presignGet(String relativePath, String fileName, String contentType, boolean download, Duration ttl) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return s3.presignGet(bucket, key(relativePath), ttl, Map.of(
                "response-content-disposition", (download ? "attachment" : "inline") + "; filename*=UTF-8''" + encoded,
                "response-content-type", contentType));
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
