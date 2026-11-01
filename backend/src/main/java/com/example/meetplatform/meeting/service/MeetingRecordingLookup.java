package com.example.meetplatform.meeting.service;

import java.util.UUID;

/**
 * Whether a meeting is being recorded right now, so join pages and rooms can say so to everyone who arrives.
 * Implemented by the recording module, which keeps the dependency direction recording -> meeting.
 */
public interface MeetingRecordingLookup {

    /** Any recording of the meeting (room or participant) is starting, running or stopping. */
    boolean isRecording(UUID meetingId);
}
