package com.example.meetplatform.participant.controller;

import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.participant.dto.RoomStateDtos.AudienceAccessView;
import com.example.meetplatform.participant.dto.RoomStateDtos.HandRequest;
import com.example.meetplatform.participant.dto.RoomStateDtos.ReactionRequest;
import com.example.meetplatform.participant.dto.RoomStateDtos.RoomStateView;
import com.example.meetplatform.participant.service.RoomStateService;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import java.util.UUID;

/** Live room state and participant signals. Guests authenticate with {@code X-Participant-Token}. */
@Tag(name = "Meeting participation")
@RestController
@RequestMapping("/api/v1/meetings/{meetingId}")
public class RoomStateController {

    private final RoomStateService roomState;
    private final CurrentUserProvider currentUser;

    public RoomStateController(RoomStateService roomState, CurrentUserProvider currentUser) {
        this.roomState = roomState;
        this.currentUser = currentUser;
    }

    @GetMapping("/room-state")
    public ResponseEntity<RoomStateView> state(@PathVariable UUID meetingId, @RequestParam UUID participantId,
                                               @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER,
                                                       required = false) String token) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(roomState.state(meetingId, participantId, token, currentUser.current()));
    }

    @GetMapping("/audience-access")
    public ResponseEntity<AudienceAccessView> audienceAccess(@PathVariable UUID meetingId,
                                                             @RequestParam UUID participantId,
                                                             @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER,
                                                                     required = false) String token) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(roomState.audienceAccess(meetingId, participantId, token, currentUser.current()));
    }

    @PostMapping("/signals/hand")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hand(@PathVariable UUID meetingId, @Valid @RequestBody HandRequest request,
                     @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER, required = false)
                     String token) {
        roomState.hand(meetingId, request.participantId(), request.raised(), token, currentUser.current());
    }

    @PostMapping("/signals/reaction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reaction(@PathVariable UUID meetingId, @Valid @RequestBody ReactionRequest request,
                         @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER, required = false)
                         String token) {
        roomState.reaction(meetingId, request.participantId(), request.emoji(), token, currentUser.current());
    }
}
