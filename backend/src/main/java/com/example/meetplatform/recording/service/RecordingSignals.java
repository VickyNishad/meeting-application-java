package com.example.meetplatform.recording.service;

import com.example.meetplatform.recording.entity.Recording;

import java.util.HashMap;
import java.util.Map;

/**
 * Payloads of the {@code recording} data topic for server-side recordings. They name the recording's kind (and,
 * for a participant recording, whose it is) so clients never mistake one person's recording stopping for the
 * room recording stopping.
 */
final class RecordingSignals {

    private RecordingSignals() {
    }

    static Map<String, Object> of(Recording r, String state) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "recording");
        payload.put("kind", r.getKind());
        payload.put("state", state);
        if (r.isAutoStarted()) {
            payload.put("auto", true);
        }
        if (r.isParticipantRecording() && r.getParticipantId() != null) {
            payload.put("participantId", r.getParticipantId().toString());
            payload.put("displayName", r.getSubjectName());
        }
        return payload;
    }
}
