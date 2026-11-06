package com.example.meetplatform.common.storage;

import com.example.meetplatform.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Object storage on AWS S3 ({@code S3_ENDPOINT} empty, IAM role or access keys) or an S3-compatible server such as
 * MinIO ({@code S3_ENDPOINT} set, access keys). Uploads use the internal endpoint; presigned URLs are signed for the
 * public endpoint because the host name is part of the signature (browsers cannot reach "minio:9000").
 */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "s3", matchIfMissing = true)
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Operations s3;
    private final AppProperties.Storage props;
    private final boolean aws;
    private final Clock clock;

    public S3ObjectStorage(AppProperties properties, Clock clock) {
        this.props = properties.storage();
        this.clock = clock;
        this.aws = S3Operations.isBlank(props.endpoint());
        this.s3 = new S3Operations(props.endpoint(), props.publicEndpoint(), props.region(), props.accessKey(),
                props.secretKey(), "File storage is unavailable.");
        log.info("Object storage: {}", aws ? "AWS S3 in " + props.region() : "S3-compatible server " + props.endpoint());
    }

    /** A local S3-compatible server gets its buckets here. An AWS bucket is checked, never created or changed. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureBuckets() {
        for (String bucket : new LinkedHashSet<>(List.of(props.recordingsBucket(), props.filesBucket()))) {
            try {
                if (s3.bucketExists(bucket)) {
                    continue;
                }
                if (aws) {
                    log.error("S3 bucket {} does not exist: uploads and downloads will fail until it does", bucket);
                } else {
                    s3.createBucket(bucket, props.region());
                    log.info("Created private bucket {}", bucket);
                }
            } catch (Exception e) {
                log.warn("Object storage not reachable at startup; bucket {} not verified: {}", bucket, e.getMessage());
            }
        }
    }

    @Override
    public void put(String bucket, String key, InputStream content, long size, String contentType) {
        s3.put(bucket, key, content, size, contentType);
    }

    @Override
    public void delete(String bucket, String key) {
        s3.delete(bucket, key);
    }

    @Override
    public boolean exists(String bucket, String key) {
        return s3.exists(bucket, key);
    }

    @Override
    public PresignedUrl presignGet(String bucket, String key, Duration ttl, String downloadFileName) {
        Map<String, String> params = Map.of();
        if (downloadFileName != null) {
            String encoded = URLEncoder.encode(downloadFileName, StandardCharsets.UTF_8).replace("+", "%20");
            params = Map.of("response-content-disposition", "attachment; filename*=UTF-8''" + encoded);
        }
        return new PresignedUrl(s3.presignGet(bucket, key, ttl, params), clock.instant().plus(ttl));
    }
}
