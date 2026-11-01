package com.example.meetplatform.media.adapter.livekit;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.media.dto.MediaParticipant;
import com.example.meetplatform.media.dto.MediaPermissions;
import com.example.meetplatform.media.dto.MediaTokenRequest;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.media.dto.MediaWebhookEvent.EgressStatus;
import com.example.meetplatform.media.dto.MediaWebhookEvent.EgressUpdate;
import com.example.meetplatform.media.dto.TrackSource;
import com.example.meetplatform.media.service.MediaService;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.CanUpdateOwnMetadata;
import io.livekit.server.EgressServiceClient;
import io.livekit.server.EncodedOutputs;
import io.livekit.server.Hidden;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import io.livekit.server.WebhookReceiver;
import livekit.LivekitEgress;
import livekit.LivekitModels;
import livekit.LivekitWebhook;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** LiveKit implementation of the media port: server API calls, token signing and webhook verification. */
@Component
public class LiveKitMediaAdapter implements MediaService {

    private static final Logger log = LoggerFactory.getLogger(LiveKitMediaAdapter.class);
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    /**
     * Extra SFU seats beyond the meeting capacity. The API enforces the real limit (hosts and co-hosts are exempt
     * from it); the SFU limit is only a backstop, so it must leave room for moderators joining a full meeting.
     */
    static final int MODERATOR_HEADROOM = 10;

    private final AppProperties.LiveKit props;
    private final RoomServiceClient rooms;
    private final EgressServiceClient egress;
    private final WebhookReceiver webhookReceiver;

    public LiveKitMediaAdapter(AppProperties properties) {
        this.props = properties.livekit();
        Supplier<OkHttpClient> http = () -> new OkHttpClient.Builder()
                .connectTimeout(props.requestTimeout())
                .readTimeout(props.requestTimeout())
                .callTimeout(props.requestTimeout().multipliedBy(2))
                .build();
        this.rooms = RoomServiceClient.createClient(props.apiUrl(), props.apiKey(), props.apiSecret(), http);
        this.egress = EgressServiceClient.createClient(props.apiUrl(), props.apiKey(), props.apiSecret(), http);
        this.webhookReceiver = new WebhookReceiver(props.apiKey(), props.apiSecret());
    }

    @Override
    public String clientUrl() {
        return props.publicUrl();
    }

    @Override
    public void createRoom(String roomName, int maxParticipants) {
        execute("createRoom", rooms.createRoom(roomName, props.emptyRoomTimeoutSeconds(),
                maxParticipants + MODERATOR_HEADROOM, null, null, null, null, null, null));
    }

    @Override
    public void deleteRoom(String roomName) {
        Response<Void> response = call("deleteRoom", rooms.deleteRoom(roomName));
        if (!response.isSuccessful() && response.code() != 404) {
            throw failure("deleteRoom", response);
        }
    }

    @Override
    public String issueToken(MediaTokenRequest request) {
        AccessToken token = new AccessToken(props.apiKey(), props.apiSecret());
        token.setIdentity(request.identity());
        token.setName(request.displayName());
        token.setMetadata(request.metadata());
        token.setTtl(request.ttl().toMillis());
        MediaPermissions p = request.permissions();
        token.addGrants(
                new RoomJoin(true),
                new RoomName(request.roomName()),
                new CanSubscribe(p.canSubscribe()),
                new CanPublish(p.canPublish()),
                new CanPublishData(p.canPublishData()),
                new CanPublishSources(p.publishSources().stream().map(TrackSource::grantName).toList()),
                new CanUpdateOwnMetadata(false));
        if (p.hidden()) {
            token.addGrants(new Hidden(true));
        }
        return token.toJwt();
    }

    @Override
    public List<MediaParticipant> listParticipants(String roomName) {
        Response<List<LivekitModels.ParticipantInfo>> response = call("listParticipants", rooms.listParticipants(roomName));
        if (response.code() == 404) {
            return List.of();
        }
        if (!response.isSuccessful() || response.body() == null) {
            throw failure("listParticipants", response);
        }
        return response.body().stream()
                .map(p -> new MediaParticipant(p.getIdentity(), p.getName(), unmutedSources(p)))
                .toList();
    }

    @Override
    public boolean muteTrack(String roomName, String identity, TrackSource source) {
        LivekitModels.ParticipantInfo participant = execute("getParticipant", rooms.getParticipant(roomName, identity));
        boolean muted = false;
        for (LivekitModels.TrackInfo track : participant.getTracksList()) {
            if (matches(track.getSource(), source) && !track.getMuted()) {
                execute("mutePublishedTrack", rooms.mutePublishedTrack(roomName, identity, track.getSid(), true));
                muted = true;
            }
        }
        return muted;
    }

    @Override
    public void removeParticipant(String roomName, String identity) {
        Response<Void> response = call("removeParticipant", rooms.removeParticipant(roomName, identity));
        if (!response.isSuccessful() && response.code() != 404) {
            throw failure("removeParticipant", response);
        }
    }

    @Override
    public void updateParticipant(String roomName, String identity, String metadata, MediaPermissions permissions) {
        LivekitModels.ParticipantPermission.Builder permission = null;
        if (permissions != null) {
            permission = LivekitModels.ParticipantPermission.newBuilder()
                    .setCanSubscribe(permissions.canSubscribe())
                    .setCanPublish(permissions.canPublish())
                    .setCanPublishData(permissions.canPublishData())
                    .setCanUpdateMetadata(false)
                    .setHidden(permissions.hidden());
            for (TrackSource source : permissions.publishSources()) {
                permission.addCanPublishSources(toProto(source));
            }
        }
        Response<LivekitModels.ParticipantInfo> response = call("updateParticipant", rooms.updateParticipant(
                roomName, identity, null, metadata, permission == null ? null : permission.build(), null));
        if (!response.isSuccessful() && response.code() != 404) {
            throw failure("updateParticipant", response);
        }
    }

    @Override
    public void renameParticipant(String roomName, String identity, String displayName) {
        Response<LivekitModels.ParticipantInfo> response = call("updateParticipant", rooms.updateParticipant(
                roomName, identity, displayName, null, null, null));
        if (!response.isSuccessful() && response.code() != 404) {
            throw failure("updateParticipant", response);
        }
    }

    @Override
    public void sendData(String roomName, String topic, byte[] payload, List<String> destinationIdentities) {
        execute("sendData", rooms.sendData(roomName, payload, LivekitModels.DataPacket.Kind.RELIABLE, List.of(),
                destinationIdentities, topic));
    }

    @Override
    public String startRoomRecording(String roomName, String filepath, String layout, boolean audioOnly) {
        // No upload block is set: the egress worker writes to the object storage configured in its own
        // egress.yaml, so storage credentials never travel through API calls.
        LivekitEgress.EncodedFileOutput output = LivekitEgress.EncodedFileOutput.newBuilder()
                .setFileType(audioOnly ? LivekitEgress.EncodedFileType.OGG : LivekitEgress.EncodedFileType.MP4)
                .setFilepath(filepath)
                .build();
        LivekitEgress.EgressInfo info = execute("startRoomCompositeEgress",
                // customBaseUrl is a non-null Kotlin parameter: "" selects the default recording template.
                () -> egress.startRoomCompositeEgress(roomName, output, layout, null, null, audioOnly, false, ""));
        return info.getEgressId();
    }

    @Override
    public String startParticipantRecording(String roomName, String identity, String filepath) {
        LivekitEgress.EncodedFileOutput output = LivekitEgress.EncodedFileOutput.newBuilder()
                .setFileType(LivekitEgress.EncodedFileType.MP4)
                .setFilepath(filepath)
                .build();
        // screenShare=false: the participant's camera and microphone (LiveKit records one or the other per egress).
        LivekitEgress.EgressInfo info = execute("startParticipantEgress",
                () -> egress.startParticipantEgress(roomName, identity, new EncodedOutputs(output, null, null, null),
                        false));
        return info.getEgressId();
    }

    @Override
    public void stopRecording(String egressId) {
        execute("stopEgress", egress.stopEgress(egressId));
    }

    @Override
    public Optional<EgressUpdate> egressStatus(String egressId) {
        List<LivekitEgress.EgressInfo> found = execute("listEgress", () -> egress.listEgress(null, egressId, null));
        return found == null || found.isEmpty() ? Optional.empty() : Optional.of(toUpdate(found.get(0)));
    }

    private static EgressUpdate toUpdate(LivekitEgress.EgressInfo info) {
        LivekitEgress.FileInfo file = info.getFileResultsCount() > 0 ? info.getFileResults(0) : null;
        return new EgressUpdate(info.getEgressId(), map(info.getStatus()), emptyToNull(info.getError()),
                file == null ? null : file.getFilename(),
                file == null ? null : file.getSize(),
                file == null ? null : file.getDuration() / NANOS_PER_SECOND,
                info.getStartedAt() == 0 ? null : nanosToInstant(info.getStartedAt()),
                info.getEndedAt() == 0 ? null : nanosToInstant(info.getEndedAt()));
    }

    @Override
    public MediaWebhookEvent parseWebhook(String body, String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new ApiException(ErrorCode.WEBHOOK_SIGNATURE_INVALID, "Missing webhook signature.");
        }
        LivekitWebhook.WebhookEvent event;
        try {
            event = webhookReceiver.receive(body, authorizationHeader);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.WEBHOOK_SIGNATURE_INVALID, "Invalid webhook signature.");
        }
        String roomName = event.hasRoom() ? event.getRoom().getName() : null;
        EgressUpdate egressUpdate = null;
        if (event.hasEgressInfo()) {
            LivekitEgress.EgressInfo info = event.getEgressInfo();
            roomName = roomName != null ? roomName : info.getRoomName();
            egressUpdate = toUpdate(info);
        }
        return new MediaWebhookEvent(event.getId(), typeOf(event.getEvent()), event.getEvent(), roomName,
                event.hasParticipant() ? event.getParticipant().getIdentity() : null, egressUpdate,
                Instant.ofEpochSecond(event.getCreatedAt()),
                event.hasParticipant() ? emptyToNull(event.getParticipant().getSid()) : null);
    }

    // ------------------------------------------------------------------ helpers

    private static MediaWebhookEvent.Type typeOf(String event) {
        return switch (event) {
            case "room_started" -> MediaWebhookEvent.Type.ROOM_STARTED;
            case "room_finished" -> MediaWebhookEvent.Type.ROOM_FINISHED;
            case "participant_joined" -> MediaWebhookEvent.Type.PARTICIPANT_JOINED;
            case "participant_left" -> MediaWebhookEvent.Type.PARTICIPANT_LEFT;
            case "egress_started" -> MediaWebhookEvent.Type.EGRESS_STARTED;
            case "egress_updated" -> MediaWebhookEvent.Type.EGRESS_UPDATED;
            case "egress_ended" -> MediaWebhookEvent.Type.EGRESS_ENDED;
            default -> MediaWebhookEvent.Type.OTHER;
        };
    }

    private static EgressStatus map(LivekitEgress.EgressStatus status) {
        return switch (status) {
            case EGRESS_STARTING -> EgressStatus.STARTING;
            case EGRESS_ACTIVE -> EgressStatus.ACTIVE;
            case EGRESS_ENDING -> EgressStatus.ENDING;
            case EGRESS_COMPLETE, EGRESS_LIMIT_REACHED -> EgressStatus.COMPLETE;
            default -> EgressStatus.FAILED;
        };
    }

    private static Set<TrackSource> unmutedSources(LivekitModels.ParticipantInfo p) {
        Set<TrackSource> sources = EnumSet.noneOf(TrackSource.class);
        for (LivekitModels.TrackInfo track : p.getTracksList()) {
            if (!track.getMuted()) {
                for (TrackSource s : TrackSource.values()) {
                    if (matches(track.getSource(), s)) {
                        sources.add(s);
                    }
                }
            }
        }
        return sources;
    }

    private static boolean matches(LivekitModels.TrackSource proto, TrackSource source) {
        return proto == toProto(source)
                || (source == TrackSource.SCREEN_SHARE && proto == LivekitModels.TrackSource.SCREEN_SHARE_AUDIO);
    }

    private static LivekitModels.TrackSource toProto(TrackSource source) {
        return switch (source) {
            case CAMERA -> LivekitModels.TrackSource.CAMERA;
            case MICROPHONE -> LivekitModels.TrackSource.MICROPHONE;
            case SCREEN_SHARE -> LivekitModels.TrackSource.SCREEN_SHARE;
            case SCREEN_SHARE_AUDIO -> LivekitModels.TrackSource.SCREEN_SHARE_AUDIO;
        };
    }

    private static Instant nanosToInstant(long nanos) {
        return Instant.ofEpochSecond(nanos / NANOS_PER_SECOND, nanos % NANOS_PER_SECOND);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** SDK argument checks (e.g. Kotlin null-safety) throw before any call is made; surface them as 503, not 500. */
    private <T> T execute(String operation, Supplier<Call<T>> call) {
        try {
            return execute(operation, call.get());
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("LiveKit {} could not be invoked", operation, e);
            throw new ApiException(ErrorCode.MEDIA_PROVIDER_UNAVAILABLE, "The media server request could not be made.", e);
        }
    }

    private <T> T execute(String operation, Call<T> call) {
        Response<T> response = call(operation, call);
        if (!response.isSuccessful()) {
            throw failure(operation, response);
        }
        return response.body();
    }

    private <T> Response<T> call(String operation, Call<T> call) {
        try {
            return call.execute();
        } catch (IOException | RuntimeException e) {
            log.warn("LiveKit {} failed: {}", operation, e.getMessage());
            throw new ApiException(ErrorCode.MEDIA_PROVIDER_UNAVAILABLE, "The media server is unavailable.", e);
        }
    }

    private static ApiException failure(String operation, Response<?> response) {
        String detail;
        try (var errorBody = response.errorBody()) {
            detail = errorBody == null ? "" : errorBody.string();
        } catch (IOException e) {
            detail = "";
        }
        log.warn("LiveKit {} returned HTTP {}: {}", operation, response.code(), detail);
        if (response.code() == 404) {
            return new ApiException(ErrorCode.NOT_FOUND, "The media room or participant no longer exists.");
        }
        return new ApiException(ErrorCode.MEDIA_PROVIDER_UNAVAILABLE, "The media server rejected the request.");
    }
}
