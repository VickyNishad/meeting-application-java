package com.example.meetplatform.media.service;

import com.example.meetplatform.media.dto.MediaParticipant;
import com.example.meetplatform.media.dto.MediaPermissions;
import com.example.meetplatform.media.dto.MediaTokenRequest;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.media.dto.TrackSource;

import java.util.List;
import java.util.Optional;

/**
 * Port to the real-time media server (SFU). Business modules depend on this interface only; the LiveKit
 * adapter is the production implementation. Media never flows through the API server - only control calls.
 */
public interface MediaService {

    /** WebSocket URL clients connect to (returned with join tokens). */
    String clientUrl();

    /** {@code maxParticipants} is the meeting capacity enforced by the API; adapters may add headroom for moderators. */
    void createRoom(String roomName, int maxParticipants);

    /** Disconnects everyone and closes the room. Missing rooms are ignored. */
    void deleteRoom(String roomName);

    /** Signs a short-lived join token. The API secret never leaves the server. */
    String issueToken(MediaTokenRequest request);

    List<MediaParticipant> listParticipants(String roomName);

    /** Server-side mute of published tracks of the given source. Returns false if nothing was published. */
    boolean muteTrack(String roomName, String identity, TrackSource source);

    void removeParticipant(String roomName, String identity);

    void updateParticipant(String roomName, String identity, String metadata, MediaPermissions permissions);

    /** Changes the name other participants see (webinar attendees are shown as "Attendee"). Missing participants are ignored. */
    void renameParticipant(String roomName, String identity, String displayName);

    /** Reliable data message on {@code topic}; {@code destinationIdentities} empty means everyone in the room. */
    void sendData(String roomName, String topic, byte[] payload, List<String> destinationIdentities);

    /** Starts a composite recording of the room into the egress worker's configured object storage. Returns egress id. */
    String startRoomRecording(String roomName, String filepath, String layout, boolean audioOnly);

    /** Records one participant's own camera and microphone into a separate file. Returns egress id. */
    String startParticipantRecording(String roomName, String identity, String filepath);

    void stopRecording(String egressId);

    /** Current state of an egress job, or empty when the media server no longer knows it. */
    Optional<MediaWebhookEvent.EgressUpdate> egressStatus(String egressId);

    /** Verifies the webhook signature and body hash; throws WEBHOOK_SIGNATURE_INVALID on failure. */
    MediaWebhookEvent parseWebhook(String body, String authorizationHeader);
}
