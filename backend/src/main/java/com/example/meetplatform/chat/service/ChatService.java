package com.example.meetplatform.chat.service;

import com.example.meetplatform.chat.dto.ChatDtos.ChatMessageView;
import com.example.meetplatform.chat.entity.ChatMessage;
import com.example.meetplatform.chat.repository.ChatMessageRepository;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.security.RateLimiter;
import com.example.meetplatform.common.util.Tokens;
import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.service.MeetingAccessService;
import com.example.meetplatform.meeting.service.MeetingPermissions;
import com.example.meetplatform.meeting.service.MeetingService;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.participant.service.ParticipantAccessService;
import com.example.meetplatform.participant.service.RoomAudience;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.service.RoomSessionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Meeting chat. Messages are persisted first and then relayed by the server over the media data channel,
 * so the sender identity in a message is always the authenticated participant, never client-asserted.
 */
@Service
public class ChatService {

    private static final int MAX_PAGE = 200;

    private final ChatMessageRepository messages;
    private final ParticipantAccessService participantAccess;
    private final MeetingService meetings;
    private final MeetingPermissions permissions;
    private final RoomSessionService roomSessions;
    private final RoomAudience audience;
    private final MeetingAccessService meetingAccess;
    private final MeetingParticipantRepository participants;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public ChatService(ChatMessageRepository messages, ParticipantAccessService participantAccess,
                       MeetingService meetings, MeetingPermissions permissions, RoomSessionService roomSessions,
                       RoomAudience audience, MeetingAccessService meetingAccess,
                       MeetingParticipantRepository participants, RateLimiter rateLimiter, Clock clock) {
        this.messages = messages;
        this.participantAccess = participantAccess;
        this.meetings = meetings;
        this.permissions = permissions;
        this.roomSessions = roomSessions;
        this.audience = audience;
        this.meetingAccess = meetingAccess;
        this.participants = participants;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @Transactional
    public ChatMessageView send(UUID meetingId, UUID participantId, String text, String token, Optional<CurrentUser> user) {
        MeetingParticipant sender = participantAccess.resolvePresent(meetingId, participantId, token, user);
        Meeting meeting = meetings.require(meetingId);
        if (!permissions.has(sender.getRole(), MeetingPermissions.CHAT)
                || (!meeting.isChatEnabled() && !sender.getRole().isModerator())) {
            throw ApiException.forbidden("Chat is disabled for you in this meeting.");
        }
        rateLimiter.check("chat", participantId.toString(), 20, Duration.ofSeconds(10));
        MeetingRoom room = roomSessions.active(meetingId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEETING_NOT_ACTIVE, "The meeting is not live."));
        // A webinar attendee writes to the stage only; the stage writes to everyone.
        String audienceKind = RoomAudience.stageOnly(meeting, sender) ? ChatMessage.AUDIENCE_STAGE : ChatMessage.AUDIENCE_ALL;
        ChatMessage message = messages.save(new ChatMessage(meetingId, room.getId(), sender.getId(),
                sender.getDisplayName(), text.strip(), clock.instant(), audienceKind));
        ChatMessageView view = ChatMessageView.of(message);
        audience.announce(meeting, room, RoomSignals.TOPIC_CHAT, Map.of("type", "chat", "message", view), sender);
        return view;
    }

    /**
     * Meeting chat history. In a webinar the stage sees every message; anyone else sees messages for everyone plus
     * their own messages to the stage, never other attendees' messages.
     */
    @Transactional(readOnly = true)
    public List<ChatMessageView> history(UUID meetingId, UUID participantId, String token, Optional<CurrentUser> user,
                                         Instant before, Integer limit) {
        participantAccess.requireMeetingViewer(meetingId, participantId, token, user);
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), MAX_PAGE);
        Instant until = before == null ? clock.instant().plusSeconds(1) : before;
        Meeting meeting = meetings.require(meetingId);
        Optional<MeetingParticipant> viewer = viewerParticipation(meetingId, participantId, token, user);
        List<ChatMessage> page;
        // Stage-only messages stay stage-only even if the meeting is no longer a webinar.
        if (isStage(meeting, viewer, user)) {
            page = messages.findByMeetingIdAndCreatedAtBeforeOrderByCreatedAtDesc(meetingId, until, PageRequest.of(0, size));
        } else {
            page = messages.findVisibleToAttendee(meetingId, until, ownParticipations(meetingId, viewer, user),
                    PageRequest.of(0, size));
        }
        // Oldest first for display.
        return page.reversed().stream().map(ChatMessageView::of).toList();
    }

    /** The caller's participation proven by participant id + secret (or ownership), if any. */
    private Optional<MeetingParticipant> viewerParticipation(UUID meetingId, UUID participantId, String token,
                                                             Optional<CurrentUser> user) {
        if (participantId != null) {
            return Optional.of(participantAccess.resolve(meetingId, participantId, token, user));
        }
        if (token != null && !token.isBlank()) {
            return participants.findByMeetingIdAndWaitTokenHash(meetingId, Tokens.sha256(token));
        }
        return Optional.empty();
    }

    /**
     * The host, or a co-host present in the live session now (the rule of the participant list). A moderator role in
     * an earlier session, or in a participation that has left or was removed, does not open stage-only messages.
     */
    private boolean isStage(Meeting meeting, Optional<MeetingParticipant> viewer, Optional<CurrentUser> user) {
        if (user.isPresent() && meetingAccess.isHost(meeting, user.get().userId())) {
            return true;
        }
        return roomSessions.active(meeting.getId()).map(room -> {
            boolean viewerOnStage = viewer.filter(p -> p.isPresent() && p.getRole().isModerator()
                    && room.getId().equals(p.getRoomId())).isPresent();
            return viewerOnStage || user.map(u -> participants.findByRoomIdAndStateIn(room.getId(),
                            MeetingParticipant.PRESENT).stream()
                    .anyMatch(p -> u.userId().equals(p.getUserId()) && p.getRole().isModerator())).orElse(false);
        }).orElse(false);
    }

    /** Every participation of the viewer in this meeting (a signed-in person may have several over time). */
    private List<UUID> ownParticipations(UUID meetingId, Optional<MeetingParticipant> viewer, Optional<CurrentUser> user) {
        UUID userId = user.map(CurrentUser::userId).orElse(viewer.map(MeetingParticipant::getUserId).orElse(null));
        List<UUID> own = new java.util.ArrayList<>();
        viewer.ifPresent(p -> own.add(p.getId()));
        if (userId != null) {
            participants.findByMeetingIdOrderByCreatedAtAsc(meetingId).stream()
                    .filter(p -> userId.equals(p.getUserId()))
                    .forEach(p -> own.add(p.getId()));
        }
        // "in ()" is not valid SQL: a random id matches nothing.
        return own.isEmpty() ? List.of(UUID.randomUUID()) : own;
    }
}
