package com.example.meetplatform.media;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.media.adapter.livekit.LiveKitMediaAdapter;
import com.example.meetplatform.media.dto.MediaPermissions;
import com.example.meetplatform.media.dto.MediaTokenRequest;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.media.dto.TrackSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the real LiveKit SDK: token signing and webhook verification need no running server. */
class LiveKitMediaAdapterTest {

    private static final String KEY = "devkey";
    private static final String SECRET = "devsecret-0123456789-0123456789-0123456789";

    private final LiveKitMediaAdapter adapter = new LiveKitMediaAdapter(properties());

    private static AppProperties properties() {
        AppProperties props = mock(AppProperties.class);
        when(props.livekit()).thenReturn(new AppProperties.LiveKit("ws://localhost:7880", "http://localhost:7880",
                KEY, SECRET, Duration.ofMinutes(10), 300, Duration.ofSeconds(5)));
        return props;
    }

    @Test
    void joinTokenCarriesOnlyTheGrantedPermissions() {
        long issuedAt = System.currentTimeMillis();
        String jwt = adapter.issueToken(new MediaTokenRequest("room-1", "participant-1", "Ada", "{\"role\":\"GUEST\"}",
                new MediaPermissions(true, true, EnumSet.of(TrackSource.MICROPHONE, TrackSource.CAMERA)),
                Duration.ofMinutes(10)));

        DecodedJWT decoded = JWT.require(Algorithm.HMAC256(SECRET)).withIssuer(KEY).build().verify(jwt);
        assertThat(decoded.getSubject()).isEqualTo("participant-1");
        Map<String, Object> video = decoded.getClaim("video").asMap();
        assertThat(video).containsEntry("room", "room-1").containsEntry("roomJoin", true)
                .containsEntry("canPublish", true).containsEntry("canSubscribe", true);
        assertThat(((List<?>) video.get("canPublishSources")).stream().map(String::valueOf).toList())
                .containsExactlyInAnyOrder("camera", "microphone");
        // Clients never receive administrative grants.
        assertThat(video).doesNotContainKeys("roomAdmin", "roomCreate", "roomRecord");
        assertThat(decoded.getExpiresAt().getTime() - issuedAt)
                .isLessThanOrEqualTo(Duration.ofMinutes(10).toMillis() + 1000);
    }

    @Test
    void validWebhookSignatureIsAccepted() throws Exception {
        String body = "{\"event\":\"participant_joined\",\"id\":\"EV_1\",\"createdAt\":\"1767225600\","
                + "\"room\":{\"name\":\"room-1\"},\"participant\":{\"identity\":\"p-1\"}}";
        MediaWebhookEvent event = adapter.parseWebhook(body, sign(body, SECRET));
        assertThat(event.id()).isEqualTo("EV_1");
        assertThat(event.type()).isEqualTo(MediaWebhookEvent.Type.PARTICIPANT_JOINED);
        assertThat(event.roomName()).isEqualTo("room-1");
        assertThat(event.participantIdentity()).isEqualTo("p-1");
    }

    @Test
    void tamperedBodyOrForeignSecretIsRejected() throws Exception {
        String body = "{\"event\":\"room_finished\",\"id\":\"EV_2\",\"room\":{\"name\":\"room-1\"}}";
        String signature = sign(body, SECRET);
        assertThatThrownBy(() -> adapter.parseWebhook(body.replace("room-1", "room-2"), signature))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        assertThatThrownBy(() -> adapter.parseWebhook(body, sign(body, "another-secret-0123456789-0123456789")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> adapter.parseWebhook(body, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void sdkFailuresSurfaceAsMediaProviderUnavailableNotServerErrors() {
        // Regression: a null Kotlin non-null argument (customBaseUrl) used to escape as an NPE / HTTP 500.
        AppProperties props = mock(AppProperties.class);
        when(props.livekit()).thenReturn(new AppProperties.LiveKit("ws://localhost:1", "http://127.0.0.1:1",
                KEY, SECRET, Duration.ofMinutes(10), 300, Duration.ofSeconds(1)));
        LiveKitMediaAdapter unreachable = new LiveKitMediaAdapter(props);
        assertThatThrownBy(() -> unreachable.startRoomRecording("room", "recordings/x.mp4", "grid", false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.MEDIA_PROVIDER_UNAVAILABLE);
    }

    private static String sign(String body, String secret) throws Exception {
        String hash = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        return JWT.create().withIssuer(KEY).withClaim("sha256", hash)
                .withExpiresAt(new java.util.Date(System.currentTimeMillis() + 60_000))
                .sign(Algorithm.HMAC256(secret));
    }
}
