package com.example.meetplatform.participant.service;

import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.service.MeetingParticipationLookup;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.entity.MeetingParticipant.State;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.room.entity.MeetingRoom;
import com.example.meetplatform.room.repository.MeetingRoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class ParticipationLookupService implements MeetingParticipationLookup {

    private final MeetingParticipantRepository participants;
    // The repository, not RoomSessionService: that service depends on MeetingAccessService, which depends on us.
    private final MeetingRoomRepository rooms;

    public ParticipationLookupService(MeetingParticipantRepository participants, MeetingRoomRepository rooms) {
        this.participants = participants;
        this.rooms = rooms;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasParticipated(UUID meetingId, UUID userId) {
        return userId != null && participants.wasAdmitted(meetingId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MeetingRole> liveSessionRole(UUID meetingId, UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return rooms.findFirstByMeetingIdAndStatus(meetingId, MeetingRoom.Status.ACTIVE)
                .flatMap(room -> participants.findFirstByRoomIdAndUserIdOrderByCreatedAtDesc(room.getId(), userId))
                .filter(p -> p.getState() != State.REMOVED && p.getState() != State.DENIED)
                .map(MeetingParticipant::getRole);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MeetingRole> presentRole(UUID meetingId, UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return rooms.findFirstByMeetingIdAndStatus(meetingId, MeetingRoom.Status.ACTIVE)
                .flatMap(room -> participants.findFirstByRoomIdAndUserIdOrderByCreatedAtDesc(room.getId(), userId))
                .filter(MeetingParticipant::isPresent)
                .map(MeetingParticipant::getRole);
    }
}
