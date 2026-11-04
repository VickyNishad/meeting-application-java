package com.example.meetplatform.participant.controller;

import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.participant.dto.ParticipantDtos.JoinRequest;
import com.example.meetplatform.participant.dto.ParticipantDtos.JoinResponse;
import com.example.meetplatform.participant.dto.ParticipantDtos.LeaveRequest;
import com.example.meetplatform.participant.dto.ParticipantDtos.ParticipantView;
import com.example.meetplatform.participant.service.JoinService;
import com.example.meetplatform.participant.service.ParticipantLifecycleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

@Tag(name = "Meeting participation")
@RestController
@RequestMapping("/api/v1/meetings/{meetingId}")
public class ParticipantController {

    public static final String PARTICIPANT_TOKEN_HEADER = "X-Participant-Token";

    private final JoinService joinService;
    private final ParticipantLifecycleService lifecycle;
    private final CurrentUserProvider currentUser;

    public ParticipantController(JoinService joinService, ParticipantLifecycleService lifecycle,
                                 CurrentUserProvider currentUser) {
        this.joinService = joinService;
        this.lifecycle = lifecycle;
        this.currentUser = currentUser;
    }

    @PostMapping("/join")
    public ResponseEntity<JoinResponse> join(@PathVariable UUID meetingId,
                                             @Valid @RequestBody(required = false) JoinRequest request,
                                             HttpServletRequest http) {
        JoinRequest body = request == null ? new JoinRequest(null, null, null) : request;
        JoinResponse response = joinService.join(meetingId, body, currentUser.current(), ClientInfo.from(http));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }

    @GetMapping("/join-status")
    public ResponseEntity<JoinResponse> joinStatus(@PathVariable UUID meetingId, @RequestParam UUID participantId,
                                                   @RequestParam(required = false) String waitToken,
                                                   @RequestHeader(value = PARTICIPANT_TOKEN_HEADER, required = false)
                                                   String headerToken) {
        String token = headerToken != null ? headerToken : waitToken;
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(joinService.status(meetingId, participantId, token, currentUser.current()));
    }

    @PostMapping("/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable UUID meetingId, @Valid @RequestBody LeaveRequest request,
                      @RequestHeader(value = PARTICIPANT_TOKEN_HEADER, required = false) String token) {
        lifecycle.leave(meetingId, request.participantId(), token, currentUser.current());
    }

    @PostMapping("/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@PathVariable UUID meetingId) {
        lifecycle.end(meetingId, currentUser.require());
    }

    @GetMapping("/participants")
    public List<ParticipantView> participants(@PathVariable UUID meetingId) {
        return lifecycle.list(meetingId, currentUser.require());
    }
}
