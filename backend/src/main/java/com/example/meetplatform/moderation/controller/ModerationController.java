package com.example.meetplatform.moderation.controller;

import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.meeting.dto.MeetingDtos.MeetingView;
import com.example.meetplatform.moderation.dto.ModerationDtos.AuditEntry;
import com.example.meetplatform.moderation.dto.ModerationDtos.LockRequest;
import com.example.meetplatform.moderation.dto.ModerationDtos.MediaPermissionsRequest;
import com.example.meetplatform.moderation.dto.ModerationDtos.MuteRequest;
import com.example.meetplatform.moderation.dto.ModerationDtos.ParticipantMediaRequest;
import com.example.meetplatform.moderation.dto.ModerationDtos.RequestUnmuteRequest;
import com.example.meetplatform.moderation.dto.ModerationDtos.RoleRequest;
import com.example.meetplatform.moderation.dto.ModerationDtos.SettingsRequest;
import com.example.meetplatform.participant.dto.RoomStateDtos.MediaPermissionsView;
import com.example.meetplatform.participant.dto.RoomStateDtos.ParticipantMedia;
import com.example.meetplatform.moderation.service.ModerationService;
import com.example.meetplatform.participant.dto.ParticipantDtos.ParticipantView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Moderation")
@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/moderation")
public class ModerationController {

    private final ModerationService service;
    private final CurrentUserProvider currentUser;

    public ModerationController(ModerationService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/waiting")
    public List<ParticipantView> waiting(@PathVariable UUID meetingId) {
        return service.waiting(meetingId, currentUser.require());
    }

    @PostMapping("/waiting/{participantId}/admit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void admit(@PathVariable UUID meetingId, @PathVariable UUID participantId) {
        service.admit(meetingId, participantId, currentUser.require());
    }

    @PostMapping("/waiting/{participantId}/deny")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deny(@PathVariable UUID meetingId, @PathVariable UUID participantId) {
        service.deny(meetingId, participantId, currentUser.require());
    }

    @PostMapping("/waiting/admit-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void admitAll(@PathVariable UUID meetingId) {
        service.admitAll(meetingId, currentUser.require());
    }

    @PostMapping("/participants/{participantId}/mute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mute(@PathVariable UUID meetingId, @PathVariable UUID participantId,
                     @Valid @RequestBody MuteRequest request) {
        service.mute(meetingId, participantId, request.source(), currentUser.require());
    }

    @PostMapping("/mute-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void muteAll(@PathVariable UUID meetingId) {
        service.muteAll(meetingId, currentUser.require());
    }

    @PostMapping("/participants/{participantId}/request-unmute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestUnmute(@PathVariable UUID meetingId, @PathVariable UUID participantId,
                              @Valid @RequestBody RequestUnmuteRequest request) {
        service.requestUnmute(meetingId, participantId, request.source(), currentUser.require());
    }

    @PostMapping("/participants/{participantId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID meetingId, @PathVariable UUID participantId) {
        service.remove(meetingId, participantId, currentUser.require());
    }

    @PostMapping("/participants/{participantId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void role(@PathVariable UUID meetingId, @PathVariable UUID participantId,
                     @Valid @RequestBody RoleRequest request) {
        service.changeRole(meetingId, participantId, request.role(), currentUser.require());
    }

    @PostMapping("/lock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lock(@PathVariable UUID meetingId, @Valid @RequestBody LockRequest request) {
        service.lock(meetingId, request.locked(), currentUser.require());
    }

    @PutMapping("/settings")
    public MeetingView settings(@PathVariable UUID meetingId, @Valid @RequestBody SettingsRequest request) {
        return service.settings(meetingId, request, currentUser.require());
    }

    /** Meeting-wide media permissions (host only). */
    @PutMapping("/media-permissions")
    public MediaPermissionsView mediaPermissions(@PathVariable UUID meetingId,
                                                 @Valid @RequestBody MediaPermissionsRequest request) {
        return service.mediaPermissions(meetingId, request, currentUser.require());
    }

    /** One participant's media overrides (host only). */
    @PutMapping("/participants/{participantId}/media-permissions")
    public ParticipantMedia participantMediaPermissions(@PathVariable UUID meetingId, @PathVariable UUID participantId,
                                                        @Valid @RequestBody ParticipantMediaRequest request) {
        return service.participantMediaPermissions(meetingId, participantId, request, currentUser.require());
    }

    @GetMapping("/audit")
    public PageResponse<AuditEntry> audit(@PathVariable UUID meetingId, @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return service.auditTrail(meetingId, currentUser.require(), page, size);
    }
}
