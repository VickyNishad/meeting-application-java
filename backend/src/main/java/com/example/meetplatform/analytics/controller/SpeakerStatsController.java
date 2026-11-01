package com.example.meetplatform.analytics.controller;

import com.example.meetplatform.analytics.dto.SpeakerStatsDtos.MeetingSpeakerStats;
import com.example.meetplatform.analytics.dto.SpeakerStatsDtos.SpeakerReport;
import com.example.meetplatform.analytics.service.SpeakerStatsService;
import com.example.meetplatform.common.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Speaking time")
@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/speaker-stats")
public class SpeakerStatsController {

    private final SpeakerStatsService speakerStats;
    private final CurrentUserProvider currentUser;

    public SpeakerStatsController(SpeakerStatsService speakerStats, CurrentUserProvider currentUser) {
        this.speakerStats = speakerStats;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@PathVariable UUID meetingId, @Valid @RequestBody SpeakerReport report) {
        speakerStats.report(meetingId, report, currentUser.require());
    }

    @GetMapping
    public MeetingSpeakerStats stats(@PathVariable UUID meetingId) {
        return speakerStats.forMeeting(meetingId, currentUser.require());
    }
}
