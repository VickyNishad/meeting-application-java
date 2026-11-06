package com.example.meetplatform.common.storage;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;

/** Private object storage (AWS S3, S3-compatible server, local disk). Objects are only exposed through short-lived signed URLs. */
public interface ObjectStorage {

    void put(String bucket, String key, InputStream content, long size, String contentType);

    /** Deleting an object that is already gone is not an error. */
    void delete(String bucket, String key);

    /** False when the object is gone (e.g. expired by a lifecycle rule). A storage failure throws instead. */
    boolean exists(String bucket, String key);

    /**
     * @param downloadFileName when non-null the URL forces {@code Content-Disposition: attachment}
     */
    PresignedUrl presignGet(String bucket, String key, Duration ttl, String downloadFileName);

    record PresignedUrl(String url, Instant expiresAt) {
    }
}
