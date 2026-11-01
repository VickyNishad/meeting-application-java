package com.example.meetplatform.common.storage;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;

/** S3-compatible private object storage. Objects are only ever exposed through short-lived presigned URLs. */
public interface ObjectStorage {

    void put(String bucket, String key, InputStream content, long size, String contentType);

    void delete(String bucket, String key);

    /**
     * @param downloadFileName when non-null the URL forces {@code Content-Disposition: attachment}
     */
    PresignedUrl presignGet(String bucket, String key, Duration ttl, String downloadFileName);

    record PresignedUrl(String url, Instant expiresAt) {
    }
}
