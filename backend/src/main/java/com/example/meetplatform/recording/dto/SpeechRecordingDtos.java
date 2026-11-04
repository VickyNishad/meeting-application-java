package com.example.meetplatform.recording.dto;

import com.example.meetplatform.recording.entity.SpeechRecording;
import jakarta.validation.constraints.Max;
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
    /**
     * {@code recordingStartedMsAgo}: how long before this request the browser began recording the turn (it asks for
     * the clip only once it heard a voice), so the clip is dated from its real start. Optional; at most 10 s count.
     */
    public record StartRequest(@NotNull UUID participantId,
                               @NotNull @Pattern(regexp = "(?i)(video|audio)/webm(;.*)?") String format,
                               @Min(0) @Max(600_000) Long recordingStartedMsAgo) {
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
    /** Filter values. Dates are YYYY-MM-DD folder dates (newest first); the filter follows Date -> Meeting -> Participant. */
    public record Facets(List<MeetingOption> meetings, List<ParticipantOption> participants, List<String> dates) {
    }

    /** A meeting filter value and the dates it has clips on (the meeting filter follows the date). */
    public record MeetingOption(UUID id, String label, List<String> dates) {
    }

    /**
     * A participant filter value: the meeting it participated in and the dates it has clips on (the participant filter
     * follows the meeting and the date), and whether it is that meeting's host. The label is the plain name.
     */
    public record ParticipantOption(UUID id, String label, UUID meetingId, boolean host, List<String> dates) {
    }

    public record AccessUrl(String url, Instant expiresAt) {
    }
}
