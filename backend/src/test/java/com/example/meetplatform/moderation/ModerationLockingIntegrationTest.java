package com.example.meetplatform.moderation;

import com.example.meetplatform.media.dto.MediaParticipant;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.media.dto.TrackSource;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Media-server calls of host controls run outside the meeting row lock; read-only polls take no lock at all. */
class ModerationLockingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private String meetingId;
    private String roomName;
    private String guestId;
    private SignedIn host;

    @BeforeEach
    void liveMeetingWithAConnectedGuest() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        host = users.create(mvc, "Lock Host");
        meetingId = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Locks", "type", "INSTANT", "timeZone", "UTC"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        roomName = json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("roomName").asString();
        JsonNode guest = json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("displayName", "Guest"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        guestId = guest.get("participantId").asString();
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_lock_" + guestId,
                MediaWebhookEvent.Type.PARTICIPANT_JOINED, "participant_joined", roomName, guestId, null, Instant.now()));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }

    @Test
    void waitingRoomPollDoesNotWaitForTheMeetingRowLock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("select id from meetings where id = ? for update", UUID.class, UUID.fromString(meetingId));
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> poll = CompletableFuture.supplyAsync(() -> {
                try {
                    return mvc.perform(get("/api/v1/meetings/" + meetingId + "/moderation/waiting")
                            .header("Authorization", host.bearer())).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            // With FOR UPDATE the poll would block until the other transaction ends.
            assertThat(poll.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void muteRemoveAndRoleChangeCallTheMediaServerOutsideAnyTransaction() throws Exception {
        clearInvocations(media); // the setup's join pushed the guest's current rights once
        AtomicBoolean sfuCalledInTransaction = new AtomicBoolean(false);
        when(media.muteTrack(anyString(), anyString(), any())).thenAnswer(inv -> {
            sfuCalledInTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        when(media.listParticipants(roomName)).thenAnswer(inv -> {
            sfuCalledInTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(new MediaParticipant(guestId, "Guest", Set.of(TrackSource.MICROPHONE)));
        });
        doAnswer(inv -> {
            sfuCalledInTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(media).updateParticipant(eq(roomName), eq(guestId), anyString(), any());
        doAnswer(inv -> {
            sfuCalledInTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(media).removeParticipant(roomName, guestId);

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + guestId + "/mute")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("source", "MICROPHONE"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/mute-all").header("Authorization", host.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/meetings/" + meetingId + "/moderation/settings")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("screenSharePolicy", "HOSTS_ONLY"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + guestId + "/remove")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isNoContent());

        verify(media, times(2)).muteTrack(roomName, guestId, TrackSource.MICROPHONE);
        verify(media).updateParticipant(eq(roomName), eq(guestId), anyString(), any());
        verify(media).removeParticipant(roomName, guestId);
        assertThat(sfuCalledInTransaction).isFalse();
        assertThat(jdbc.queryForObject("select state from meeting_participants where id = ?", String.class,
                UUID.fromString(guestId))).isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where resource_id = ? and action in"
                        + " ('MOD_MUTE', 'MOD_MUTE_ALL', 'MOD_REMOVE')", Long.class, UUID.fromString(meetingId)))
                .isEqualTo(3L);
    }
}
