package com.example.meetplatform.scheduling.dto;

import com.example.meetplatform.common.validation.ValidTimeZone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class AvailabilityDtos {

    private AvailabilityDtos() {
    }

    public record WeeklyRuleDto(@NotNull DayOfWeek dayOfWeek, @NotNull LocalTime startTime, @NotNull LocalTime endTime) {
    }

    public record AvailabilityView(String timeZone, List<WeeklyRuleDto> weeklyRules) {
    }

    public record UpdateAvailabilityRequest(@NotNull @ValidTimeZone String timeZone,
                                            @NotNull @Size(max = 50) List<@Valid WeeklyRuleDto> weeklyRules) {
    }

    public record ExceptionRequest(@NotNull LocalDate date, @NotNull Boolean available, LocalTime startTime,
                                   LocalTime endTime, @Size(max = 255) String reason) {
    }

    public record ExceptionView(UUID id, LocalDate date, boolean available, LocalTime startTime, LocalTime endTime,
                                String reason) {
    }
}
