package com.example.meetplatform.common.exception;

import org.springframework.http.HttpStatus;

/** Machine-readable error codes; the catalogue is documented in docs/04-api-contracts.md. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_TIME_ZONE(HttpStatus.BAD_REQUEST),
    INVALID_RECURRENCE(HttpStatus.BAD_REQUEST),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    WEBHOOK_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED),

    FORBIDDEN(HttpStatus.FORBIDDEN),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN),
    NOT_INVITED(HttpStatus.FORBIDDEN),
    GUESTS_NOT_ALLOWED(HttpStatus.FORBIDDEN),
    MEETING_LOCKED(HttpStatus.FORBIDDEN),
    MEETING_PASSWORD_INVALID(HttpStatus.FORBIDDEN),

    NOT_FOUND(HttpStatus.NOT_FOUND),

    CONFLICT(HttpStatus.CONFLICT),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT),
    OPTIMISTIC_LOCK_CONFLICT(HttpStatus.CONFLICT),
    MEETING_NOT_ACTIVE(HttpStatus.CONFLICT),
    MEETING_ENDED(HttpStatus.CONFLICT),
    MEETING_CANCELLED(HttpStatus.CONFLICT),
    MEETING_FULL(HttpStatus.CONFLICT),
    RECORDING_ALREADY_ACTIVE(HttpStatus.CONFLICT),
    RECORDING_NOT_ACTIVE(HttpStatus.CONFLICT),
    SLOT_UNAVAILABLE(HttpStatus.CONFLICT),
    BOOKING_NOT_MODIFIABLE(HttpStatus.CONFLICT),
    SLUG_TAKEN(HttpStatus.CONFLICT),
    BOOKING_PAGE_HAS_UPCOMING_BOOKINGS(HttpStatus.CONFLICT),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT),
    /** Another moderator's client is the one reporting speaking time for this session. */
    SPEAKER_REPORTER_MISMATCH(HttpStatus.CONFLICT),

    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    FILE_TYPE_NOT_ALLOWED(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    MEETING_PASSWORD_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    MEDIA_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    /** The recording disk is (nearly) full: nothing more is accepted until space is freed. */
    INSUFFICIENT_STORAGE(HttpStatus.INSUFFICIENT_STORAGE);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Default code for a status produced by the framework rather than by application code. */
    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404, 405 -> NOT_FOUND;
            case 409 -> CONFLICT;
            case 413 -> FILE_TOO_LARGE;
            case 415 -> FILE_TYPE_NOT_ALLOWED;
            case 429 -> RATE_LIMITED;
            case 503 -> MEDIA_PROVIDER_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_ERROR : MALFORMED_REQUEST;
        };
    }
}
