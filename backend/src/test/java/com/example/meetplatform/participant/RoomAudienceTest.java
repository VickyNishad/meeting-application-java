package com.example.meetplatform.participant;

import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
import com.example.meetplatform.participant.service.RoomAudience;
import com.example.meetplatform.room.entity.MeetingRoom;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA56-4 / QA56-10: the participant count is read when the announcement is sent (after the changes committed, not
 * inside one of them), and the joins and leaves of one room within the debounce window send one announcement.
 */
class RoomAudienceTest {

    private final MeetingParticipantRepository participants = mock(MeetingParticipantRepository.class);
    private final RoomSignals signals = mock(RoomSignals.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final RoomAudience audience = new RoomAudience(participants, signals, scheduler, transactions,
            Duration.ofMillis(250), Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void joinsWithinTheWindowAreAnnouncedOnceWithTheCountAtSendingTime() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        MeetingRoom room = mock(MeetingRoom.class);
        UUID roomId = UUID.randomUUID();
        when(room.getId()).thenReturn(roomId);
        when(room.getLivekitRoomName()).thenReturn("mtg-1");
        Meeting meeting = mock(Meeting.class);

        // Three joins (no transaction active here, so each is "after commit" at once).
        audience.announcePresence(meeting, room);
        audience.announcePresence(meeting, room);
        audience.announcePresence(meeting, room);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(1)).schedule(task.capture(), any(Instant.class));
        verify(signals, never()).broadcast(any(), any(), anyMap());

        // By the time it is sent, all three have committed: the count is read then.
        when(participants.countPeople(eq(roomId), any(), any())).thenReturn(3L);
        task.getValue().run();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(signals, times(1)).broadcast(eq("mtg-1"), eq(RoomSignals.TOPIC_PRESENCE), payload.capture());
        assertThat(payload.getValue().get("count")).isEqualTo(3);

        // The next change schedules a new announcement.
        audience.announcePresence(meeting, room);
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }
}
