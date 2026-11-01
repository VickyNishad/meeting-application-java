package com.example.meetplatform.media.service;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-originated real-time notifications delivered over the media server's data channel
 * (see "Real-time data channel contract" in docs/04-api-contracts.md). Participants cannot publish data themselves,
 * so everything on these topics comes from the server.
 *
 * <p>Signals are sent after the surrounding transaction commits, so clients never see a state change that was
 * rolled back. Delivery is best-effort: a failed signal is logged, never fails the business operation.
 * The exception is {@link #broadcastNow}, for notices that are a precondition of the operation itself.
 *
 * <p>Signals to the whole room carry {@code seq}/{@code stream} = {@code all}; signals to the stage (host and
 * co-hosts) carry {@code stream} = {@code stage}. Numbers are assigned when the signal is sent, so a client that
 * sees a gap knows it missed one and reloads the room state.
 */
@Component
public class RoomSignals {

    public static final String TOPIC_CHAT = "chat";
    public static final String TOPIC_MODERATION = "moderation";
    public static final String TOPIC_RECORDING = "recording";
    public static final String TOPIC_FILES = "files";
    public static final String TOPIC_HAND = "hand";
    public static final String TOPIC_REACTIONS = "reactions";
    public static final String TOPIC_PRESENCE = "presence";

    private static final Logger log = LoggerFactory.getLogger(RoomSignals.class);

    private final MediaService media;
    private final JsonMapper jsonMapper;
    private final RoomSequence sequence;

    public RoomSignals(MediaService media, JsonMapper jsonMapper, RoomSequence sequence) {
        this.media = media;
        this.jsonMapper = jsonMapper;
        this.sequence = sequence;
    }

    public void broadcast(String roomName, String topic, Map<String, ?> payload) {
        send(roomName, topic, payload, List.of(), RoomSequence.STREAM_ALL);
    }

    /** To specific participants (e.g. one person being asked to unmute): not numbered. */
    public void sendTo(String roomName, String topic, Map<String, ?> payload, List<String> identities) {
        if (!identities.isEmpty()) {
            send(roomName, topic, payload, identities, null);
        }
    }

    /**
     * To the stage of a HOST_AND_SELF_ONLY session (host and co-hosts present), plus {@code extraIdentities} (e.g.
     * the attendee who sent it). The stage copy is numbered on the stage stream; the extra recipients get theirs
     * unnumbered, since the gaps between stage numbers would tell an attendee how much other attendees are doing.
     */
    public void sendToStage(String roomName, String topic, Map<String, ?> payload, List<String> stageIdentities,
                            List<String> extraIdentities) {
        List<String> extra = notOnStage(stageIdentities, extraIdentities);
        if (!stageIdentities.isEmpty()) {
            send(roomName, topic, payload, stageIdentities, RoomSequence.STREAM_STAGE);
        }
        if (!extra.isEmpty()) {
            send(roomName, topic, payload, extra, null);
        }
    }

    /**
     * Delivers to everyone in the room right now (not after commit) and throws
     * {@code MEDIA_PROVIDER_UNAVAILABLE} if the room cannot be notified. Used for recording consent: the
     * indicator must reach the room before capture starts, and capture must not start if it cannot.
     */
    public void broadcastNow(String roomName, String topic, Map<String, ?> payload) {
        deliverNow(roomName, topic, payload, List.of(), RoomSequence.STREAM_ALL);
    }

    /**
     * {@link #broadcastNow} for the stage of a HOST_AND_SELF_ONLY session plus {@code extraIdentities} (who get no
     * numbered copy, see {@link #sendToStage}). Nobody to tell is not an error (an empty destination list would mean
     * the whole room, so nothing is sent).
     */
    public void sendToStageNow(String roomName, String topic, Map<String, ?> payload, List<String> stageIdentities,
                               List<String> extraIdentities) {
        List<String> to = audience(stageIdentities, extraIdentities);
        if (to.isEmpty()) {
            return;
        }
        deliverNow(roomName, topic, payload, to, null);
        if (!stageIdentities.isEmpty()) {
            send(roomName, topic, payload, stageIdentities, RoomSequence.STREAM_STAGE);
        }
    }

    /**
     * The notice goes out now, unnumbered (clients apply it as it comes), and again numbered after commit (if {@code numberedStream}): a number
     * taken before commit could be covered by a room-state snapshot that does not yet show the change.
     */
    private void deliverNow(String roomName, String topic, Map<String, ?> payload, List<String> identities,
                            String numberedStream) {
        try {
            media.sendData(roomName, topic, jsonMapper.writeValueAsBytes(payload), identities);
        } catch (RuntimeException e) {
            log.warn("Could not deliver '{}' notice to room {}: {}", topic, roomName, e.getMessage());
            throw new ApiException(ErrorCode.MEDIA_PROVIDER_UNAVAILABLE,
                    "The meeting room could not be notified. Try again.", e);
        }
        if (numberedStream != null) {
            send(roomName, topic, payload, identities, numberedStream);
        }
    }

    private void send(String roomName, String topic, Map<String, ?> payload, List<String> identities, String stream) {
        if (roomName == null) {
            return;
        }
        Runnable delivery = () -> {
            try {
                byte[] bytes = jsonMapper.writeValueAsBytes(numbered(roomName, payload, stream));
                media.sendData(roomName, topic, bytes, identities);
            } catch (RuntimeException e) {
                log.warn("Could not deliver '{}' signal to room {}: {}", topic, roomName, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delivery.run();
                }
            });
        } else {
            delivery.run();
        }
    }

    private Map<String, ?> numbered(String roomName, Map<String, ?> payload, String stream) {
        if (stream == null) {
            return payload;
        }
        return sequence.next(roomName, stream).<Map<String, ?>>map(n -> {
            Map<String, Object> copy = new HashMap<>(payload);
            copy.put("stream", stream);
            copy.put("seq", n.seq());
            copy.put("epoch", n.epoch());
            return copy;
        }).orElse(payload);
    }

    private static List<String> notOnStage(List<String> stageIdentities, List<String> extraIdentities) {
        return extraIdentities.stream().filter(id -> !stageIdentities.contains(id)).distinct().toList();
    }

    private static List<String> audience(List<String> stageIdentities, List<String> extraIdentities) {
        return java.util.stream.Stream.concat(stageIdentities.stream(), extraIdentities.stream()).distinct().toList();
    }
}
