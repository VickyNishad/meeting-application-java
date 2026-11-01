package com.example.meetplatform.booking.dto;

import com.example.meetplatform.booking.entity.Booking;
import com.example.meetplatform.common.validation.ValidTimeZone;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BookingDtos {

    private BookingDtos() {
    }

    public static final String SLUG_PATTERN = "^[a-z0-9][a-z0-9-]{1,58}[a-z0-9]$";

    public record BookingPageRequest(
            @NotBlank @Pattern(regexp = SLUG_PATTERN, message = "must be 3-60 lowercase letters, digits or hyphens") String slug,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String description,
            @NotNull @Min(5) @Max(480) Integer durationMinutes,
            @Min(5) @Max(480) Integer slotIntervalMinutes,
            @Min(0) @Max(43200) Integer minNoticeMinutes,
            @Min(1) @Max(365) Integer maxAdvanceDays,
            @Min(0) @Max(240) Integer bufferBeforeMinutes,
            @Min(0) @Max(240) Integer bufferAfterMinutes,
            MediaMode mediaMode,
            Boolean active) {
    }

    public record UpdateBookingPageRequest(
            @Pattern(regexp = SLUG_PATTERN, message = "must be 3-60 lowercase letters, digits or hyphens") String slug,
            @Size(min = 1, max = 200) String title,
            @Size(max = 5000) String description,
            @Min(5) @Max(480) Integer durationMinutes,
            @Min(5) @Max(480) Integer slotIntervalMinutes,
            @Min(0) @Max(43200) Integer minNoticeMinutes,
            @Min(1) @Max(365) Integer maxAdvanceDays,
            @Min(0) @Max(240) Integer bufferBeforeMinutes,
            @Min(0) @Max(240) Integer bufferAfterMinutes,
            MediaMode mediaMode,
            Boolean active,
            Long version) {
    }

    public record BookingPageView(UUID id, String slug, String title, String description, int durationMinutes,
                                  int slotIntervalMinutes, int minNoticeMinutes, int maxAdvanceDays,
                                  int bufferBeforeMinutes, int bufferAfterMinutes, MediaMode mediaMode, boolean active,
                                  Instant createdAt, long version) {
    }

    public record PublicBookingPage(String slug, String title, String description, int durationMinutes,
                                    String hostName, String hostTimeZone, MediaMode mediaMode) {
    }

    public record SlotView(Instant start, Instant end) {
    }

    public record SlotsResponse(String timeZone, List<SlotView> slots) {
    }

    public record CreateBookingRequest(
            @NotBlank @Pattern(regexp = SLUG_PATTERN) String slug,
            @NotNull Instant startTime,
            @NotBlank @Size(max = 120) String inviteeName,
            @NotBlank @Email @Size(max = 320) String inviteeEmail,
            @NotNull @ValidTimeZone String inviteeTimeZone,
            @Size(max = 2000) String notes) {
    }

    public record CancelBookingRequest(@Size(max = 500) String reason) {
    }

    public record RescheduleRequest(@NotNull Instant startTime) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BookingView(UUID id, String bookingPageSlug, String bookingPageTitle, Booking.Status status,
                              Instant startTime, Instant endTime, String inviteeName, String inviteeEmail,
                              String inviteeTimeZone, String notes, UUID meetingId, String joinUrl, Instant createdAt,
                              String manageToken) {
    }
}
