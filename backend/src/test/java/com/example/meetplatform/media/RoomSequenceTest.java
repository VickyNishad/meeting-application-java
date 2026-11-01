package com.example.meetplatform.media;

import com.example.meetplatform.media.service.RoomSequence;
import com.example.meetplatform.room.event.RoomEvents;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** QA-B1: lost counters come back under a new epoch, so clients reload instead of dropping "old" numbers. */
class RoomSequenceTest {

    private final RoomSequence sequence = new RoomSequence(null, "memory");

    @Test
    void numbersCountUpWithinOneEpochPerStream() {
        RoomSequence.Number a = sequence.next("room", RoomSequence.STREAM_ALL).orElseThrow();
        RoomSequence.Number b = sequence.next("room", RoomSequence.STREAM_ALL).orElseThrow();
        RoomSequence.Number s = sequence.next("room", RoomSequence.STREAM_STAGE).orElseThrow();
        assertThat(b.seq()).isEqualTo(a.seq() + 1);
        assertThat(b.epoch()).isEqualTo(a.epoch()).isNotBlank();
        assertThat(s.seq()).isEqualTo(1L);
        assertThat(sequence.current("room", RoomSequence.STREAM_ALL)).isEqualTo(b);
    }

    @Test
    void countersRecreatedAfterTheSessionEndsHaveANewEpoch() {
        RoomSequence.Number before = sequence.next("room", RoomSequence.STREAM_ALL).orElseThrow();
        sequence.next("room", RoomSequence.STREAM_ALL);
        sequence.onSessionEnded(new RoomEvents.SessionEnded(UUID.randomUUID(), UUID.randomUUID(), "room", "ENDED"));
        RoomSequence.Number after = sequence.next("room", RoomSequence.STREAM_ALL).orElseThrow();
        assertThat(after.seq()).isEqualTo(1L);
        assertThat(after.epoch()).isNotEqualTo(before.epoch());
    }

    @Test
    void roomsAreIndependent() {
        sequence.next("a", RoomSequence.STREAM_ALL);
        assertThat(sequence.next("b", RoomSequence.STREAM_ALL).orElseThrow().seq()).isEqualTo(1L);
    }
}
