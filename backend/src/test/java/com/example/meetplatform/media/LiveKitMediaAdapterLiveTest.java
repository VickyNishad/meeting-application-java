package com.example.meetplatform.media;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.media.adapter.livekit.LiveKitMediaAdapter;
import com.example.meetplatform.media.dto.MediaPermissions;
import com.example.meetplatform.media.dto.TrackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Calls every server API used by the platform against a REAL LiveKit server. Opt-in:
 * {@code LIVEKIT_LIVE_URL=http://127.0.0.1:7880 LIVEKIT_LIVE_KEY=... LIVEKIT_LIVE_SECRET=... ./mvnw test -Dtest=LiveKitMediaAdapterLiveTest}
 */
@EnabledIfEnvironmentVariable(named = "LIVEKIT_LIVE_URL", matches = ".+")
class LiveKitMediaAdapterLiveTest {

    private final LiveKitMediaAdapter adapter = new LiveKitMediaAdapter(properties());

    private static AppProperties properties() {
        AppProperties props = mock(AppProperties.class);
        when(props.livekit()).thenReturn(new AppProperties.LiveKit("ws://unused", System.getenv("LIVEKIT_LIVE_URL"),
                System.getenv("LIVEKIT_LIVE_KEY"), System.getenv("LIVEKIT_LIVE_SECRET"), Duration.ofMinutes(10), 60,
                Duration.ofSeconds(5)));
        return props;
    }

    @Test
    void everyServerOperationWorksAgainstARealServer() {
        String room = "live-test-" + UUID.randomUUID();
        adapter.createRoom(room, 10);
        assertThat(adapter.listParticipants(room)).isEmpty();
        // Operations on identities that are not connected must fail cleanly (404 / no-op), never with a 500.
        adapter.sendData(room, "moderation", "{\"type\":\"test\"}".getBytes(StandardCharsets.UTF_8), List.of());
        adapter.sendData(room, "moderation", "{}".getBytes(StandardCharsets.UTF_8), List.of("nobody"));
        adapter.updateParticipant(room, "nobody", "{\"role\":\"CO_HOST\"}",
                new MediaPermissions(true, true, EnumSet.of(TrackSource.MICROPHONE)));
        adapter.removeParticipant(room, "nobody");
        assertThatThrownBy(() -> adapter.muteTrack(room, "nobody", TrackSource.MICROPHONE))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
        // Without an egress worker the request is rejected by the server: it must map to a clean ApiException.
        try {
            String egressId = adapter.startRoomRecording(room, "recordings/live-test.mp4", "grid", false);
            adapter.stopRecording(egressId);
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.MEDIA_PROVIDER_UNAVAILABLE);
        }
        adapter.deleteRoom(room);
        adapter.deleteRoom(room); // idempotent
    }
}
