package com.example.meetplatform.common.storage;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
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
import java.util.Map;

/**
 * MinIO / S3 implementation. Uploads go through the internal endpoint; presigned URLs are signed for the
 * public endpoint because the host name is part of the signature (browsers cannot reach "minio:9000").
 */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "s3", matchIfMissing = true)
public class MinioObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);

    private final MinioClient internalClient;
    private final MinioClient presignClient;
    private final AppProperties.Storage props;
    private final Clock clock;

    public MinioObjectStorage(AppProperties properties, Clock clock) {
        this.props = properties.storage();
        this.clock = clock;
        this.internalClient = MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .region(props.region())
                .build();
        // Region is set explicitly so presigning is a pure local computation (no network round-trip).
        this.presignClient = MinioClient.builder()
                .endpoint(props.publicEndpoint())
                .credentials(props.accessKey(), props.secretKey())
                .region(props.region())
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBuckets() {
        for (String bucket : new String[]{props.recordingsBucket(), props.filesBucket()}) {
            try {
                if (!internalClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    internalClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).region(props.region()).build());
                    log.info("Created private bucket {}", bucket);
                }
            } catch (Exception e) {
                log.warn("Object storage not reachable at startup; bucket {} not verified: {}", bucket, e.getMessage());
            }
        }
    }

    @Override
    public void put(String bucket, String key, InputStream content, long size, String contentType) {
        try {
            internalClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(key).stream(content, size, -1).contentType(contentType).build());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "File storage is unavailable.", e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            internalClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "File storage is unavailable.", e);
        }
    }

    @Override
    public PresignedUrl presignGet(String bucket, String key, Duration ttl, String downloadFileName) {
        try {
            GetPresignedObjectUrlArgs.Builder args = GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket).object(key).expiry((int) ttl.toSeconds());
            if (downloadFileName != null) {
                String encoded = URLEncoder.encode(downloadFileName, StandardCharsets.UTF_8).replace("+", "%20");
                args.extraQueryParams(Map.of("response-content-disposition",
                        "attachment; filename*=UTF-8''" + encoded));
            }
            return new PresignedUrl(presignClient.getPresignedObjectUrl(args.build()), clock.instant().plus(ttl));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "File storage is unavailable.", e);
        }
    }
}
