package com.example.meetplatform.common.storage;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.credentials.IamAwsProvider;
import io.minio.credentials.Provider;
import io.minio.credentials.StaticProvider;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import okhttp3.OkHttpClient;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * The S3 calls of this application, against AWS S3 or an S3-compatible server. The MinIO Java client is only the S3
 * library here: nothing in this class needs a MinIO server. Everything that stores objects goes through this class.
 *
 * <p>Credentials: access keys when both are configured, otherwise the server's IAM role (EC2 instance profile, ECS
 * task role or EKS web identity).
 */
public final class S3Operations {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(2);
    /** What S3 and compatible servers answer for an object or key that does not exist. */
    private static final Set<String> MISSING = Set.of("NoSuchKey", "NoSuchObject", "NotFound");

    private final MinioClient client;
    private final MinioClient presignClient;
    private final String errorMessage;

    /**
     * @param endpoint       blank = AWS S3 in {@code region}
     * @param publicEndpoint host browsers reach for presigned URLs; blank = the same as {@code endpoint}
     * @param errorMessage   what a client is told when storage fails
     */
    public S3Operations(String endpoint, String publicEndpoint, String region, String accessKey, String secretKey,
                        String errorMessage) {
        if (isBlank(region)) {
            throw new IllegalStateException("S3 storage needs a region (AWS_REGION)");
        }
        String target = isBlank(endpoint) ? "https://s3." + region + ".amazonaws.com" : endpoint;
        if (!isBlank(endpoint) && (isBlank(accessKey) || isBlank(secretKey))) {
            throw new IllegalStateException("An S3 endpoint other than AWS (" + endpoint + ") needs an access key and "
                    + "a secret key: an IAM role only works with AWS S3");
        }
        Provider credentials = credentials(accessKey, secretKey);
        this.client = build(target, region, credentials);
        this.presignClient = isBlank(publicEndpoint) || publicEndpoint.equals(target) ? client
                : build(publicEndpoint, region, credentials);
        this.errorMessage = errorMessage;
    }

    /**
     * Access keys when both are set, otherwise the IAM role. The IAM provider is used on its own: in a
     * ChainedProvider, MinIO's AwsEnvironmentProvider throws (not ProviderException) when the keys are unset or empty,
     * which would stop the chain before the IAM role is ever tried.
     */
    public static Provider credentials(String accessKey, String secretKey) {
        if (!isBlank(accessKey) && !isBlank(secretKey)) {
            return new StaticProvider(accessKey, secretKey, null);
        }
        return new IamAwsProvider(null, null);
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static MinioClient build(String endpoint, String region, Provider credentials) {
        return MinioClient.builder()
                .endpoint(endpoint)
                // Set explicitly so presigning is a pure local computation (no region lookup round-trip).
                .region(region)
                .credentialsProvider(credentials)
                // Uploads can run inside a transaction: fail fast instead of OkHttp's 5-minute defaults.
                .httpClient(new OkHttpClient.Builder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .writeTimeout(TRANSFER_TIMEOUT)
                        .readTimeout(TRANSFER_TIMEOUT)
                        .build())
                .build();
    }

    public boolean bucketExists(String bucket) throws Exception {
        return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
    }

    public void createBucket(String bucket, String region) throws Exception {
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).region(region).build());
    }

    public void put(String bucket, String key, InputStream content, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(key).stream(content, size, -1).contentType(contentType).build());
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    public void upload(String bucket, String key, Path file, String contentType) {
        try {
            client.uploadObject(UploadObjectArgs.builder()
                    .bucket(bucket).object(key).filename(file.toString()).contentType(contentType).build());
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    /** Deleting an object that is already gone is not an error. */
    public void delete(String bucket, String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    /** False when the object is gone (e.g. expired by a lifecycle rule); a storage failure is an error, not "gone". */
    public boolean exists(String bucket, String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if (MISSING.contains(e.errorResponse().code())) {
                return false;
            }
            throw unavailable(e);
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    public String presignGet(String bucket, String key, Duration ttl, Map<String, String> responseParams) {
        try {
            GetPresignedObjectUrlArgs.Builder args = GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket).object(key).expiry((int) ttl.toSeconds());
            if (!responseParams.isEmpty()) {
                args.extraQueryParams(responseParams);
            }
            return presignClient.getPresignedObjectUrl(args.build());
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    private ApiException unavailable(Exception cause) {
        return new ApiException(ErrorCode.STORAGE_UNAVAILABLE, errorMessage, cause);
    }
}
