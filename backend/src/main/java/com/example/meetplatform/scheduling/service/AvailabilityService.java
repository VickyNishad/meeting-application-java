package com.example.meetplatform.scheduling.service;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.validation.TimeZones;
import com.example.meetplatform.identity.service.UserService;
import com.example.meetplatform.scheduling.dto.AvailabilityDtos.AvailabilityView;
import com.example.meetplatform.scheduling.dto.AvailabilityDtos.ExceptionRequest;
import com.example.meetplatform.scheduling.dto.AvailabilityDtos.ExceptionView;
import com.example.meetplatform.scheduling.dto.AvailabilityDtos.UpdateAvailabilityRequest;
import com.example.meetplatform.scheduling.dto.AvailabilityDtos.WeeklyRuleDto;
import com.example.meetplatform.scheduling.entity.AvailabilityException;
import com.example.meetplatform.scheduling.entity.AvailabilityRule;
import com.example.meetplatform.scheduling.entity.AvailabilitySchedule;
import com.example.meetplatform.scheduling.repository.AvailabilityExceptionRepository;
import com.example.meetplatform.scheduling.repository.AvailabilityRuleRepository;
import com.example.meetplatform.scheduling.repository.AvailabilityScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AvailabilityService {

    private final AvailabilityScheduleRepository schedules;
    private final AvailabilityRuleRepository rules;
    private final AvailabilityExceptionRepository exceptions;
    private final UserService users;
    private final Clock clock;

    public AvailabilityService(AvailabilityScheduleRepository schedules, AvailabilityRuleRepository rules,
                               AvailabilityExceptionRepository exceptions, UserService users, Clock clock) {
        this.schedules = schedules;
        this.rules = rules;
        this.exceptions = exceptions;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AvailabilityView get(UUID userId) {
        return new AvailabilityView(timeZoneOf(userId), rules.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId).stream()
                .map(r -> new WeeklyRuleDto(r.getDayOfWeek(), r.getStartTime(), r.getEndTime())).toList());
    }

    @Transactional
    public AvailabilityView replace(CurrentUser user, UpdateAvailabilityRequest request) {
        TimeZones.require(request.timeZone());
        validateRules(request.weeklyRules());
        AvailabilitySchedule schedule = schedules.findById(user.userId())
                .orElseGet(() -> new AvailabilitySchedule(user.userId(), request.timeZone()));
        schedule.setTimeZone(request.timeZone());
        schedules.save(schedule);
        rules.deleteByUserId(user.userId());
        rules.flush();
        for (WeeklyRuleDto r : request.weeklyRules()) {
            rules.save(new AvailabilityRule(user.userId(), r.dayOfWeek(), r.startTime(), r.endTime()));
        }
        return get(user.userId());
    }

    @Transactional(readOnly = true)
    public List<ExceptionView> exceptions(UUID userId, LocalDate from, LocalDate to) {
        LocalDate start = from == null ? LocalDate.now(clock).minusDays(1) : from;
        LocalDate end = to == null ? start.plusDays(366) : to;
        return exceptions.findByUserIdAndDateBetweenOrderByDateAscStartTimeAsc(userId, start, end).stream()
                .map(AvailabilityService::view).toList();
    }

    @Transactional
    public ExceptionView addException(CurrentUser user, ExceptionRequest request) {
        boolean hasTimes = request.startTime() != null || request.endTime() != null;
        if (hasTimes && (request.startTime() == null || request.endTime() == null
                || !request.endTime().isAfter(request.startTime()))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "startTime and endTime must both be set and endTime > startTime.");
        }
        if (request.available() && !hasTimes) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Extra availability needs a startTime and endTime.");
        }
        return view(exceptions.save(new AvailabilityException(user.userId(), request.date(), request.available(),
                request.startTime(), request.endTime(), request.reason(), clock.instant())));
    }

    @Transactional
    public void deleteException(CurrentUser user, UUID exceptionId) {
        AvailabilityException e = exceptions.findById(exceptionId).filter(x -> x.getUserId().equals(user.userId()))
                .orElseThrow(() -> ApiException.notFound("Exception"));
        exceptions.delete(e);
    }

    // ------------------------------------------------------------------ for slot computation

    @Transactional(readOnly = true)
    public String timeZoneOf(UUID userId) {
        return schedules.findById(userId).map(AvailabilitySchedule::getTimeZone)
                .orElseGet(() -> users.require(userId).getTimeZone());
    }

    @Transactional(readOnly = true)
    public List<SlotCalculator.WeeklyRule> weeklyRules(UUID userId) {
        return rules.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId).stream()
                .map(r -> new SlotCalculator.WeeklyRule(r.getDayOfWeek(), r.getStartTime(), r.getEndTime())).toList();
    }

    @Transactional(readOnly = true)
    public List<SlotCalculator.DateException> dateExceptions(UUID userId, LocalDate from, LocalDate to) {
        return exceptions.findByUserIdAndDateBetweenOrderByDateAscStartTimeAsc(userId, from, to).stream()
                .map(e -> new SlotCalculator.DateException(e.getDate(), e.isAvailable(), e.getStartTime(), e.getEndTime()))
                .toList();
    }

    static void validateRules(List<WeeklyRuleDto> list) {
        Map<DayOfWeek, List<WeeklyRuleDto>> byDay = list.stream().collect(Collectors.groupingBy(WeeklyRuleDto::dayOfWeek));
        for (List<WeeklyRuleDto> day : byDay.values()) {
            List<WeeklyRuleDto> sorted = new ArrayList<>(day);
            sorted.sort(Comparator.comparing(WeeklyRuleDto::startTime));
            for (int i = 0; i < sorted.size(); i++) {
                WeeklyRuleDto r = sorted.get(i);
                if (!r.endTime().isAfter(r.startTime())) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Each rule must end after it starts.");
                }
                if (i > 0 && sorted.get(i - 1).endTime().isAfter(r.startTime())) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Rules on " + r.dayOfWeek() + " overlap.");
                }
            }
        }
    }

    private static ExceptionView view(AvailabilityException e) {
        return new ExceptionView(e.getId(), e.getDate(), e.isAvailable(), e.getStartTime(), e.getEndTime(), e.getReason());
    }
}
