package com.example.meetplatform.media.dto;

public enum TrackSource {
    CAMERA("camera"),
    MICROPHONE("microphone"),
    SCREEN_SHARE("screen_share"),
    SCREEN_SHARE_AUDIO("screen_share_audio");

    private final String grantName;

    TrackSource(String grantName) {
        this.grantName = grantName;
    }

    /** Name used in LiveKit token grants ({@code canPublishSources}). */
    public String grantName() {
        return grantName;
    }
}
