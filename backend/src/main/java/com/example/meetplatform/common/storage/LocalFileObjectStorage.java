package com.example.meetplatform.common.storage;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

/**
 * Development storage ({@code app.storage.type=local}, set by the "dev" profile): each bucket is a folder under
 * {@code app.storage.local-root} (e.g. {@code D:/meetly/meet-recordings}). Objects stay private: "presigned" URLs
 * point at {@link LocalStorageController} and carry an HMAC over bucket, key, expiry and download name.
 */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "local")
public class LocalFileObjectStorage implements ObjectStorage {

    static final String DOWNLOAD_PATH = "/api/v1/storage/local";

    private static final Logger log = LoggerFactory.getLogger(LocalFileObjectStorage.class);

    private final Path root;
    private final Set<String> buckets;
    private final String publicUrl;
    private final byte[] signingKey;
    private final Clock clock;

    public LocalFileObjectStorage(AppProperties properties, Clock clock) {
        AppProperties.Storage props = properties.storage();
        if (props.localRoot() == null || props.localRoot().isBlank()) {
            throw new IllegalStateException("app.storage.local-root must be set when app.storage.type=local");
        }
        this.root = Path.of(props.localRoot()).toAbsolutePath().normalize();
        this.buckets = Set.of(props.recordingsBucket(), props.filesBucket());
        this.publicUrl = properties.publicUrl().replaceAll("/+$", "");
        // Separate key per purpose: an access token signature can never double as a download signature.
        this.signingKey = hmac(properties.security().jwtSecret().getBytes(StandardCharsets.UTF_8),
                "local-object-storage");
        this.clock = clock;
        for (String bucket : buckets) {
            try {
                Files.createDirectories(root.resolve(bucket));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create local storage folder " + root.resolve(bucket), e);
            }
        }
        log.info("Local object storage at {}", root);
    }

    @Override
    public void put(String bucket, String key, InputStream content, long size, String contentType) {
        Path target = resolve(bucket, key);
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "File storage is unavailable.", e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            Files.deleteIfExists(resolve(bucket, key));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "File storage is unavailable.", e);
        }
    }

    @Override
    public PresignedUrl presignGet(String bucket, String key, Duration ttl, String downloadFileName) {
        resolve(bucket, key);
        Instant expiresAt = clock.instant().plus(ttl);
        long expires = expiresAt.getEpochSecond();
        String name = downloadFileName == null ? "" : downloadFileName;
        StringBuilder url = new StringBuilder(publicUrl).append(DOWNLOAD_PATH)
                .append("?bucket=").append(encode(bucket))
                .append("&key=").append(encode(key))
                .append("&expires=").append(expires);
        if (!name.isEmpty()) {
            url.append("&name=").append(encode(name));
        }
        url.append("&sig=").append(sign(bucket, key, expires, name));
        return new PresignedUrl(url.toString(), expiresAt);
    }

    /** The file a signed URL refers to; rejects a bad signature or an expired link. */
    Path verifiedPath(String bucket, String key, long expires, String name, String signature) {
        String expected = sign(bucket, key, expires, name == null ? "" : name);
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            throw ApiException.forbidden("This link is not valid.");
        }
        if (clock.instant().getEpochSecond() > expires) {
            throw ApiException.forbidden("This link has expired.");
        }
        return resolve(bucket, key);
    }

    /** bucket/key inside the storage root; anything that would escape its bucket folder is refused. */
    private Path resolve(String bucket, String key) {
        if (bucket == null || !buckets.contains(bucket) || key == null || key.isBlank()) {
            throw ApiException.notFound("File");
        }
        Path bucketDir = root.resolve(bucket);
        Path path = bucketDir.resolve(key).normalize();
        if (!path.startsWith(bucketDir) || path.equals(bucketDir)) {
            throw ApiException.notFound("File");
        }
        return path;
    }

    private String sign(String bucket, String key, long expires, String name) {
        String payload = bucket + '\n' + key + '\n' + expires + '\n' + name;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(signingKey, payload));
    }

    private static byte[] hmac(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort: a stray temp file is harmless
        }
    }
}
