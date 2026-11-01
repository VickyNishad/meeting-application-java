package com.example.meetplatform.analytics.dto;

import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SpeakerStatsDtos {

    private SpeakerStatsDtos() {
    }

    /**
     * Speaking time observed by the reporting moderator's client since its previous batch. {@code batchSeq} increases
     * per batch, so a retried batch is counted once.
     */
    public record SpeakerReport(@NotNull UUID participantId, @NotNull @Min(0) Long batchSeq,
                                @NotNull @Size(max = 300) List<@Valid @NotNull Entry> entries) {
    }

    /** One participant's speaking time in a batch. A batch covers about 10 seconds; 30 s is the accepted maximum. */
    public record Entry(@NotNull UUID participantId, @NotNull @Min(0) @Max(30_000) Long speakingMs,
                        @NotNull @Min(0) @Max(100) Integer turns) {
    }

    public record SpeakerStat(UUID roomId, UUID participantId, String displayName, MeetingRole role,
                              long speakingMs, int turns, Instant firstSpokeAt, Instant lastSpokeAt) {
    }

    /** Per session (room), speakers by speaking time, longest first. */
    public record MeetingSpeakerStats(UUID meetingId, long totalSpeakingMs, List<SpeakerStat> speakers) {
    }
}
