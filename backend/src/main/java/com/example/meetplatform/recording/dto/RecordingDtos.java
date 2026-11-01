package com.example.meetplatform.recording.dto;

import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
import com.example.meetplatform.recording.entity.Recording;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public final class RecordingDtos {

    private RecordingDtos() {
    }

    public record StartRecordingRequest(@Pattern(regexp = "^(grid|speaker)$") String layout, Boolean audioOnly) {
    }

    /**
     * {@code kind} is CLOUD (whole room) or PARTICIPANT (one person's camera and microphone, identified by
     * {@code participantId}, {@code subjectName} and, for signed-in people, {@code subjectUserId}). New recordings
     * are CLOUD only; PARTICIPANT rows are legacy. {@code layout} is grid or speaker (follows the active speaker);
     * {@code stageOnly} means the file shows only the host and co-hosts (webinar meetings).
     */
    public record RecordingView(UUID id, UUID meetingId, String meetingTitle, String kind, Recording.Status status,
                                Instant startedAt, Instant endedAt, Long durationSeconds, Long sizeBytes,
                                String failureReason, Instant retentionUntil, UserSummary startedBy,
                                boolean autoStarted, UUID participantId, UUID subjectUserId, String subjectName,
                                String layout, String contentType, boolean stageOnly) {
    }

    public record SignedUrl(String url, Instant expiresAt) {
    }

    public enum LocalState { STARTED, STOPPED }

    public record LocalRecordingNotice(@NotNull UUID participantId, @NotNull LocalState state) {
    }
}
