package com.example.meetplatform.chat.dto;

import com.example.meetplatform.chat.entity.ChatMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record SendMessageRequest(@NotNull UUID participantId, @NotBlank @Size(max = 4000) String text) {
    }

    /** {@code audience} is ALL, or STAGE for a webinar attendee's message that only the stage and its sender see. */
    public record ChatMessageView(UUID id, UUID meetingId, UUID participantId, String senderName, String text,
                                  Instant sentAt, String audience) {

        public static ChatMessageView of(ChatMessage m) {
            return new ChatMessageView(m.getId(), m.getMeetingId(), m.getParticipantId(), m.getSenderName(), m.getText(),
                    m.getCreatedAt(), m.getAudience());
        }
    }
}
