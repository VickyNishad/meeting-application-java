package com.example.meetplatform.common.exception;

/** Business error carrying a stable {@link ErrorCode}; rendered as RFC 9457 problem details. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Long retryAfterSeconds;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, null, null);
    }

    public ApiException(ErrorCode code, String detail, Throwable cause) {
        this(code, detail, null, cause);
    }

    private ApiException(ErrorCode code, String detail, Long retryAfterSeconds, Throwable cause) {
        super(detail, cause);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException rateLimited(long retryAfterSeconds) {
        return new ApiException(ErrorCode.RATE_LIMITED, "Too many requests. Try again later.", retryAfterSeconds, null);
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found.");
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(ErrorCode.FORBIDDEN, detail);
    }

    public ErrorCode code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
