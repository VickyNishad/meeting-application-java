package com.example.meetplatform.meeting.dto;

import com.example.meetplatform.common.validation.ValidTimeZone;
import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaMode;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingStatus;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingType;
import com.example.meetplatform.meeting.entity.MeetingEnums.Privacy;
import com.example.meetplatform.meeting.entity.MeetingEnums.ScreenSharePolicy;
import com.example.meetplatform.meeting.entity.MeetingEnums.VisibilityMode;
import com.example.meetplatform.meeting.entity.MeetingInvitation;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MeetingDtos {

    private MeetingDtos() {
    }

    public record CreateMeetingRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String description,
            @NotNull MeetingType type,
            MediaMode mediaMode,
            Privacy privacy,
            UUID organizationId,
            Instant startTime,
            @Min(5) @Max(1440) Integer durationMinutes,
            @NotNull @ValidTimeZone String timeZone,
            @Size(max = 512) String recurrenceRule,
            @Size(min = 4, max = 64) String password,
            Boolean waitingRoomEnabled,
            Boolean allowGuests,
            Boolean joinBeforeHost,
            Boolean muteOnEntry,
            @Min(2) @Max(300) Integer maxParticipants,
            ScreenSharePolicy screenSharePolicy,
            @Size(max = 300) List<@Email @Size(max = 320) String> inviteeEmails,
            Boolean autoRecord,
            VisibilityMode visibilityMode) {
    }

    /** Partial update: null fields are left unchanged. An empty password removes password protection. */
    public record UpdateMeetingRequest(
            @Size(min = 1, max = 200) String title,
            @Size(max = 5000) String description,
            MediaMode mediaMode,
            Privacy privacy,
            Instant startTime,
            @Min(5) @Max(1440) Integer durationMinutes,
            @ValidTimeZone String timeZone,
            @Size(max = 512) String recurrenceRule,
            @Size(max = 64) String password,
            Boolean waitingRoomEnabled,
            Boolean allowGuests,
            Boolean joinBeforeHost,
            Boolean muteOnEntry,
            @Min(2) @Max(300) Integer maxParticipants,
            ScreenSharePolicy screenSharePolicy,
            Long version,
            Boolean autoRecord,
            VisibilityMode visibilityMode) {
    }

    public record CancelMeetingRequest(@Size(max = 500) String reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MeetingView(
            UUID id, String joinCode, String joinUrl, String title, String description,
            MeetingType type, MeetingStatus status, MediaMode mediaMode, Privacy privacy,
            UUID organizationId, UserSummary host,
            Instant startTime, Instant endTime, String timeZone, String recurrenceRule,
            boolean passwordProtected, boolean waitingRoomEnabled, boolean allowGuests, boolean joinBeforeHost,
            boolean muteOnEntry, int maxParticipants, ScreenSharePolicy screenSharePolicy, boolean chatEnabled,
            boolean locked, MeetingRole myRole, Instant createdAt, long version, boolean autoRecord,
            boolean recordingInProgress, VisibilityMode visibilityMode) {
    }

    /**
     * {@code autoRecord} and {@code recordingInProgress} are public so the join page can tell people before they
     * join that they will be recorded.
     */
    public record MeetingSummaryByCode(UUID meetingId, String title, String hostName, MeetingStatus status,
                                       Instant startTime, MediaMode mediaMode, boolean passwordRequired,
                                       boolean allowGuests, boolean waitingRoomEnabled, boolean autoRecord,
                                       boolean recordingInProgress) {
    }

    public record InviteRequest(@NotEmpty @Size(max = 300) List<@Email @Size(max = 320) String> emails) {
    }

    public record InvitationView(UUID id, String email, MeetingInvitation.Status status, Instant sentAt) {
    }
}
