package com.example.meetplatform.participant;

import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6: the participant count comes from authoritative membership (JOINED, one per person), survives duplicate
 * and out-of-order events and reconnects, and is shown only as far as the visibility mode allows.
 */
class PresenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    private SignedIn host;

    @BeforeEach
    void setUp() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        host = users.create(mvc, "Presence Host");
    }

    @Test
    void concurrentJoinsCountEveryPersonOnceEvenWithASecondTab() throws Exception {
        String meetingId = meeting("EVERYONE_VISIBLE");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        joined(roomName, hostJoin.get("participantId").asString(), "PA_host");
        List<SignedIn> people = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            people.add(users.create(mvc, "Person " + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<String>> joins = new ArrayList<>();
            for (SignedIn p : people) {
                joins.add(() -> join(meetingId, p).get("participantId").asString());
            }
            List<String> pids = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(joins)) {
                pids.add(f.get());
            }
            for (String pid : pids) {
                joined(roomName, pid, "PA_" + pid);
            }
            // A second tab of the same person reuses the participation: same id, still one person.
            String again = join(meetingId, people.get(0)).get("participantId").asString();
            assertThat(again).isEqualTo(pids.get(0));
            joined(roomName, again, "PA_second_tab");
        } finally {
            pool.shutdown();
        }
        assertThat(state(meetingId, hostJoin, host).get("presentCount").asInt()).isEqualTo(11);
    }

    @Test
    void duplicateAndOutOfOrderEventsNeitherDoubleCountNorDropAPerson() throws Exception {
        String meetingId = meeting("EVERYONE_VISIBLE");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        joined(roomName, hostJoin.get("participantId").asString(), "PA_h");
        String guestPid = guestJoin(meetingId, "Guest Dup").get("participantId").asString();
        clearInvocations(media);

        joined(roomName, guestPid, "PA_g1");
        joined(roomName, guestPid, "PA_g1"); // the same join reported again (different event id)
        verify(media, times(1)).sendData(eq(roomName), eq("presence"), any(), anyList());
        assertThat(state(meetingId, hostJoin, host).get("presentCount").asInt()).isEqualTo(2);

        // Reconnect: new connection, then the old connection's disconnect arrives late.
        joined(roomName, guestPid, "PA_g2");
        left(roomName, guestPid, "PA_g1");
        assertThat(state(meetingId, hostJoin, host).get("presentCount").asInt()).isEqualTo(2);

        left(roomName, guestPid, "PA_g2");
        assertThat(state(meetingId, hostJoin, host).get("presentCount").asInt()).isEqualTo(1);
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(media, atLeastOnce()).sendData(eq(roomName), eq("presence"), payloads.capture(), anyList());
        JsonNode last = json.readTree(new String(payloads.getValue(), StandardCharsets.UTF_8));
        assertThat(last.get("count").asInt()).isEqualTo(1);
        assertThat(last.get("seq").asLong()).isPositive();
    }

    @Test
    void admittedButNeverConnectedIsNotCounted() throws Exception {
        String meetingId = meeting("EVERYONE_VISIBLE");
        JsonNode hostJoin = join(meetingId, host);
        joined(hostJoin.get("roomName").asString(), hostJoin.get("participantId").asString(), "PA_h");
        guestJoin(meetingId, "Guest Never Connects");
        assertThat(state(meetingId, hostJoin, host).get("presentCount").asInt()).isEqualTo(1);
    }

    /** QA56-3: the join of a connection that arrives after its leave does not bring back a ghost. */
    @Test
    void aLateJoinOfAnEndedConnectionIsIgnored() throws Exception {
        String meetingId = meeting("EVERYONE_VISIBLE");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        joined(roomName, hostJoin.get("participantId").asString(), "PA_h");
        String guestPid = guestJoin(meetingId, "Guest Late").get("participantId").asString();
        joined(roomName, guestPid, "PA_g1");
        left(roomName, guestPid, "PA_g1");
        joined(roomName, guestPid, "PA_g1"); // delivered late, after its leave
        assertThat(state(meetingId, hostJoin, host).get("presentCount").asInt()).isEqualTo(1);
    }

    /**
     * QA56-3/QA56-8: a second tab or role-change reconnect whose old connection's leave is processed before the new
     * connection's join takes its seat back, even in a locked room; and joining again within the window reuses the
     * participation with the role it had (a promotion is not lost).
     */
    @Test
    void aReconnectWithinTheWindowKeepsItsSeatAndRoleInALockedRoom() throws Exception {
        String meetingId = meeting("EVERYONE_VISIBLE");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        joined(roomName, hostJoin.get("participantId").asString(), "PA_h");
        SignedIn ann = users.create(mvc, "Ann Reconnects");
        String annPid = join(meetingId, ann).get("participantId").asString();
        joined(roomName, annPid, "PA_a1");
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + annPid + "/role")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("role", "CO_HOST"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/lock").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("locked", true))))
                .andExpect(status().is2xxSuccessful());
        clearInvocations(media);

        left(roomName, annPid, "PA_a1");
        JsonNode again = join(meetingId, ann);
        assertThat(again.get("participantId").asString()).isEqualTo(annPid);
        assertThat(again.get("role").asString()).isEqualTo("CO_HOST");
        joined(roomName, annPid, "PA_a2");
        verify(media, never()).removeParticipant(roomName, annPid);
        assertThat(state(meetingId, hostJoin, host).get("presentCount").asInt()).isEqualTo(2);
    }

    /** HOST_AND_SELF_ONLY: the stage gets the real count; an attendee only counts the stage and itself. */
    @Test
    void attendeesOfHostAndSelfOnlyLearnNothingAboutOtherAttendees() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        joined(roomName, hostJoin.get("participantId").asString(), "PA_h");
        List<JsonNode> guests = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            JsonNode g = guestJoin(meetingId, "Attendee " + i);
            joined(roomName, g.get("participantId").asString(), "PA_a" + i);
            guests.add(g);
        }
        assertThat(state(meetingId, hostJoin, host).get("presentCount").asInt()).isEqualTo(4);
        JsonNode attendeeView = guestState(meetingId, guests.get(0));
        assertThat(attendeeView.get("presentCount").asInt()).isEqualTo(2);
        assertThat(attendeeView.has("participants")).isFalse();

        // Presence signals go to the stage only, never to everyone.
        clearInvocations(media);
        left(roomName, guests.get(2).get("participantId").asString(), "PA_a2");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
        verify(media).sendData(eq(roomName), eq("presence"), any(), to.capture());
        assertThat(to.getValue()).containsExactly(hostJoin.get("participantId").asString());
        verify(media, never()).sendData(eq(roomName), eq("presence"), any(), eq(List.of()));
    }

    /** Performance: the room-state snapshot of a 200-person session (measured, reported; generous bound). */
    @Test
    void roomStateOfALargeSessionIsFast() throws Exception {
        String meetingId = meetingWithCapacity(300);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        joined(roomName, hostJoin.get("participantId").asString(), "PA_host");
        for (int i = 0; i < 199; i++) {
            JsonNode g = guestJoin(meetingId, "Crowd " + i);
            joined(roomName, g.get("participantId").asString(), "PA_c" + i);
        }
        state(meetingId, hostJoin, host); // warm-up
        int runs = 20;
        long start = System.nanoTime();
        JsonNode last = null;
        for (int i = 0; i < runs; i++) {
            last = state(meetingId, hostJoin, host);
        }
        double avgMs = (System.nanoTime() - start) / 1e6 / runs;
        System.out.printf("PERF room-state with 200 participants: %.1f ms average over %d requests%n", avgMs, runs);
        assertThat(last.get("presentCount").asInt()).isEqualTo(200);
        assertThat(avgMs).isLessThan(500);
    }

    // ------------------------------------------------------------------ helpers

    private String meeting(String visibility) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Presence", "type", "INSTANT", "timeZone", "UTC",
                "visibilityMode", visibility));
        return json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String meetingWithCapacity(int capacity) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Crowd", "type", "INSTANT", "timeZone", "UTC",
                "maxParticipants", capacity));
        return json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private JsonNode join(String meetingId, SignedIn user) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode guestJoin(String meetingId, String name) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("displayName", name))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode state(String meetingId, JsonNode join, SignedIn user) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/meetings/" + meetingId + "/room-state")
                        .param("participantId", join.get("participantId").asString())
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode guestState(String meetingId, JsonNode join) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/meetings/" + meetingId + "/room-state")
                        .param("participantId", join.get("participantId").asString())
                        .header("X-Participant-Token", join.get("waitToken").asString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void joined(String roomName, String identity, String sid) throws Exception {
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, identity, sid);
    }

    private void left(String roomName, String identity, String sid) throws Exception {
        webhook(MediaWebhookEvent.Type.PARTICIPANT_LEFT, roomName, identity, sid);
    }

    private synchronized void webhook(MediaWebhookEvent.Type type, String roomName, String identity, String sid)
            throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_" + UUID.randomUUID(),
                type, type.name().toLowerCase(), roomName, identity, null, Instant.now(), sid));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }
}
