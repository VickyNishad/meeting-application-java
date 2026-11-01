package com.example.meetplatform.recording.dto;

import com.example.meetplatform.recording.entity.SpeechRecording;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Speaking clips. The storage path never appears here: files are only reached through signed, short-lived URLs. */
public final class SpeechRecordingDtos {

    private SpeechRecordingDtos() {
    }

    /** {@code format}: what the browser records, "video/webm" or "audio/webm" (codec parameters are ignored). */
    public record StartRequest(@NotNull UUID participantId,
                               @NotNull @Pattern(regexp = "(?i)(video|audio)/webm(;.*)?") String format) {
    }

    /** {@code chunks}: how many chunks the client sent, to detect missing ones. */
    public record FinishRequest(@NotNull UUID participantId, @NotNull @Min(0) Integer chunks) {
    }

    public record SpeechRecordingView(UUID id, UUID meetingId, String meetingTitle, UUID participantId,
                                      String participantName, String fileName, String fileFormat, long fileSize,
                                      Instant startTime, Instant endTime, Long durationMs, String status,
                                      String failureReason, Instant createdAt) {

        public static SpeechRecordingView of(SpeechRecording r) {
            return new SpeechRecordingView(r.getId(), r.getMeetingId(), r.getMeetingTitle(), r.getParticipantId(),
                    r.getParticipantName(), r.getFileName(), r.getFileFormat(), r.getFileSize(), r.getStartTime(),
                    r.getEndTime(), r.getDurationMs(), r.getStatus().name(), r.getFailureReason(), r.getCreatedAt());
        }
    }

    /** The values the list can be filtered by, within what the viewer may see. */
    public record Facets(List<Option> meetings, List<Option> participants, List<String> dates) {
    }

    public record Option(UUID id, String label) {
    }

    public record AccessUrl(String url, Instant expiresAt) {
    }
}
