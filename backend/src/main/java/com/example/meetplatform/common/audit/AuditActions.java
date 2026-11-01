package com.example.meetplatform.common.audit;

/** Audit action and resource-type names. Kept as constants so log queries stay stable. */
public final class AuditActions {

    public static final String RESOURCE_USER = "USER";
    public static final String RESOURCE_ORGANIZATION = "ORGANIZATION";
    public static final String RESOURCE_MEETING = "MEETING";
    public static final String RESOURCE_RECORDING = "RECORDING";
    public static final String RESOURCE_BOOKING = "BOOKING";
    public static final String RESOURCE_BOOKING_PAGE = "BOOKING_PAGE";

    public static final String USER_REGISTERED = "USER_REGISTERED";
    public static final String USER_LOGIN = "USER_LOGIN";
    public static final String USER_LOGIN_FAILED = "USER_LOGIN_FAILED";
    public static final String USER_LOGOUT = "USER_LOGOUT";
    public static final String USER_EMAIL_VERIFIED = "USER_EMAIL_VERIFIED";
    public static final String USER_PASSWORD_CHANGED = "USER_PASSWORD_CHANGED";
    public static final String USER_PASSWORD_RESET = "USER_PASSWORD_RESET";
    public static final String USER_SESSION_REVOKED = "USER_SESSION_REVOKED";
    public static final String USER_REFRESH_REUSE_DETECTED = "USER_REFRESH_REUSE_DETECTED";
    public static final String USER_UPDATED_BY_ADMIN = "USER_UPDATED_BY_ADMIN";

    public static final String ORG_CREATED = "ORG_CREATED";
    public static final String ORG_UPDATED = "ORG_UPDATED";
    public static final String ORG_MEMBER_ADDED = "ORG_MEMBER_ADDED";
    public static final String ORG_MEMBER_ROLE_CHANGED = "ORG_MEMBER_ROLE_CHANGED";
    public static final String ORG_MEMBER_REMOVED = "ORG_MEMBER_REMOVED";

    public static final String MEETING_CREATED = "MEETING_CREATED";
    public static final String MEETING_UPDATED = "MEETING_UPDATED";
    public static final String MEETING_CANCELLED = "MEETING_CANCELLED";
    public static final String MEETING_STARTED = "MEETING_STARTED";
    public static final String MEETING_ENDED = "MEETING_ENDED";
    public static final String MEETING_JOIN_DENIED = "MEETING_JOIN_DENIED";

    public static final String MOD_ADMIT = "MOD_ADMIT";
    public static final String MOD_DENY = "MOD_DENY";
    public static final String MOD_MUTE = "MOD_MUTE";
    public static final String MOD_MUTE_ALL = "MOD_MUTE_ALL";
    public static final String MOD_REQUEST_UNMUTE = "MOD_REQUEST_UNMUTE";
    public static final String MOD_REMOVE = "MOD_REMOVE";
    public static final String MOD_ROLE_CHANGE = "MOD_ROLE_CHANGE";
    public static final String MOD_LOCK = "MOD_LOCK";
    public static final String MOD_SETTINGS = "MOD_SETTINGS";
    public static final String MOD_AUTO_PROMOTE = "MOD_AUTO_PROMOTE";

    public static final String RECORDING_STARTED = "RECORDING_STARTED";
    public static final String RECORDING_STOPPED = "RECORDING_STOPPED";
    public static final String RECORDING_FAILED = "RECORDING_FAILED";
    public static final String RECORDING_DELETED = "RECORDING_DELETED";
    public static final String RECORDING_LOCAL_STARTED = "RECORDING_LOCAL_STARTED";
    public static final String RECORDING_LOCAL_STOPPED = "RECORDING_LOCAL_STOPPED";
    public static final String SPEECH_RECORDING_ACCESSED = "SPEECH_RECORDING_ACCESSED";
    public static final String RESOURCE_SPEECH_RECORDING = "SPEECH_RECORDING";

    public static final String BOOKING_PAGE_CREATED = "BOOKING_PAGE_CREATED";
    public static final String BOOKING_CREATED = "BOOKING_CREATED";
    public static final String BOOKING_CANCELLED = "BOOKING_CANCELLED";
    public static final String BOOKING_RESCHEDULED = "BOOKING_RESCHEDULED";

    private AuditActions() {
    }
}
