package com.example.meetplatform.meeting.controller;

import com.example.meetplatform.common.idempotency.IdempotencyService;
import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.meeting.dto.MeetingDtos.CancelMeetingRequest;
import com.example.meetplatform.meeting.dto.MeetingDtos.CreateMeetingRequest;
import com.example.meetplatform.meeting.dto.MeetingDtos.InvitationView;
import com.example.meetplatform.meeting.dto.MeetingDtos.InviteRequest;
import com.example.meetplatform.meeting.dto.MeetingDtos.MeetingSummaryByCode;
import com.example.meetplatform.meeting.dto.MeetingDtos.MeetingView;
import com.example.meetplatform.meeting.dto.MeetingDtos.UpdateMeetingRequest;
import com.example.meetplatform.meeting.service.MeetingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Meetings")
@Validated
@RestController
@RequestMapping("/api/v1/meetings")
public class MeetingController {

    private final MeetingService service;
    private final CurrentUserProvider currentUser;
    private final IdempotencyService idempotency;

    public MeetingController(MeetingService service, CurrentUserProvider currentUser, IdempotencyService idempotency) {
        this.service = service;
        this.currentUser = currentUser;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateMeetingRequest request,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var user = currentUser.require();
        return idempotency.execute("meetings:" + user.userId(), idempotencyKey, request,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(user, request)));
    }

    @GetMapping
    public PageResponse<MeetingView> list(@RequestParam(defaultValue = "upcoming") String scope,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return service.list(currentUser.require(), scope, page, size);
    }

    @GetMapping("/{meetingId}")
    public MeetingView get(@PathVariable UUID meetingId) {
        return service.get(currentUser.require(), meetingId);
    }

    @PatchMapping("/{meetingId}")
    public MeetingView update(@PathVariable UUID meetingId, @Valid @RequestBody UpdateMeetingRequest request) {
        return service.update(currentUser.require(), meetingId, request);
    }

    @PostMapping("/{meetingId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID meetingId, @Valid @RequestBody(required = false) CancelMeetingRequest request) {
        service.cancel(currentUser.require(), meetingId, request == null ? null : request.reason());
    }

    @GetMapping("/by-code/{joinCode}")
    public MeetingSummaryByCode byCode(@PathVariable @Pattern(regexp = "^[a-zA-Z0-9]{6,16}$") String joinCode,
                                       HttpServletRequest http) {
        return service.byCode(joinCode, ClientInfo.from(http).ipAddress());
    }

    @GetMapping("/{meetingId}/invitations")
    public List<InvitationView> invitations(@PathVariable UUID meetingId) {
        return service.invitations(currentUser.require(), meetingId);
    }

    @PostMapping("/{meetingId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public void invite(@PathVariable UUID meetingId, @Valid @RequestBody InviteRequest request) {
        service.addInvitations(currentUser.require(), meetingId, request.emails());
    }
}
