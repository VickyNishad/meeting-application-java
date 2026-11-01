package com.example.meetplatform.room.controller;

import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.meeting.dto.MeetingDtos.MeetingView;
import com.example.meetplatform.meeting.dto.MeetingDtos.UpdateMeetingRequest;
import com.example.meetplatform.meeting.service.MeetingService;
import com.example.meetplatform.room.service.RoomSessionService;
import com.example.meetplatform.room.service.RoomSessionService.SessionView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Rooms")
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final MeetingService meetings;
    private final RoomSessionService sessions;
    private final CurrentUserProvider currentUser;

    public RoomController(MeetingService meetings, RoomSessionService sessions, CurrentUserProvider currentUser) {
        this.meetings = meetings;
        this.sessions = sessions;
        this.currentUser = currentUser;
    }

    @GetMapping("/personal")
    public MeetingView personal() {
        return meetings.personalRoom(currentUser.require());
    }

    @PatchMapping("/personal")
    public MeetingView updatePersonal(@Valid @RequestBody UpdateMeetingRequest request) {
        return meetings.updatePersonalRoom(currentUser.require(), request);
    }

    @GetMapping("/{meetingId}/sessions")
    public List<SessionView> sessions(@PathVariable UUID meetingId) {
        return sessions.sessions(currentUser.require(), meetingId);
    }
}
