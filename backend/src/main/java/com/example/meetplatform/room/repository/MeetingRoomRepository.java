package com.example.meetplatform.room.repository;

import com.example.meetplatform.room.entity.MeetingRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingRoomRepository extends JpaRepository<MeetingRoom, UUID> {

    Optional<MeetingRoom> findFirstByMeetingIdAndStatus(UUID meetingId, MeetingRoom.Status status);

    Optional<MeetingRoom> findByLivekitRoomName(String livekitRoomName);

    List<MeetingRoom> findByMeetingIdOrderByStartedAtDesc(UUID meetingId);

    List<MeetingRoom> findByStatus(MeetingRoom.Status status);
}
