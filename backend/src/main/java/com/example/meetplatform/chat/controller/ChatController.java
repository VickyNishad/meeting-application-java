package com.example.meetplatform.chat.controller;

import com.example.meetplatform.chat.dto.ChatDtos.ChatMessageView;
import com.example.meetplatform.chat.dto.ChatDtos.SendMessageRequest;
import com.example.meetplatform.chat.service.ChatService;
import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.participant.controller.ParticipantController;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Tag(name = "Chat")
@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/chat")
public class ChatController {

    private final ChatService service;
    private final CurrentUserProvider currentUser;

    public ChatController(ChatService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ChatMessageView> history(@PathVariable UUID meetingId,
                                         @RequestParam(required = false) UUID participantId,
                                         @RequestParam(required = false) Instant before,
                                         @RequestParam(required = false) Integer limit,
                                         @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER,
                                                 required = false) String token) {
        return service.history(meetingId, participantId, token, currentUser.current(), before, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChatMessageView send(@PathVariable UUID meetingId, @Valid @RequestBody SendMessageRequest request,
                                @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER, required = false)
                                String token) {
        return service.send(meetingId, request.participantId(), request.text(), token, currentUser.current());
    }
}
