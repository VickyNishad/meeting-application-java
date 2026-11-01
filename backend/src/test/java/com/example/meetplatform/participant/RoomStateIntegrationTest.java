package com.example.meetplatform.participant;

import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.repository.MeetingParticipantRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Room state snapshot, raised hands and reactions relayed by the server, numbered signals, and presence that
 * survives out-of-order webhooks.
 */
class RoomStateIntegrationTest extends AbstractIntegrationTest {

    private static final String TOKEN_HEADER = "X-Participant-Token";

    @Autowired
    TestUsers users;

    @Autowired
    MeetingParticipantRepository participants;

    private SignedIn host;
    private SignedIn alice;

    @BeforeEach
    void setUp() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        host = users.create(mvc, "State Host");
        alice = users.create(mvc, "State Alice");
    }

    @Test
    void snapshotShowsStageHandsAndLocalRecordersAndOnlyTheStageGetsNames() throws Exception {
        String meetingId = meeting("HOSTS_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String hostPid = hostJoin.get("participantId").asString();
        JsonNode guest = guestJoin(meetingId, "Guest Gary");
        String guestPid = guest.get("participantId").asString();
        String guestSecret = guest.get("waitToken").asString();

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/signals/hand").header(TOKEN_HEADER, guestSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", guestPid, "raised", true))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/local-recordings").header(TOKEN_HEADER, guestSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", guestPid, "state", "STARTED"))))
                .andExpect(status().isNoContent());

        JsonNode forHost = state(meetingId, hostPid, null, host);
        assertThat(forHost.get("role").asString()).isEqualTo("HOST");
        assertThat(forHost.get("visibilityMode").asString()).isEqualTo("HOST_AND_SELF_ONLY");
        assertThat(forHost.get("stageIdentities").size()).isEqualTo(1);
        assertThat(forHost.get("stageIdentities").get(0).asString()).isEqualTo(hostPid);
        assertThat(forHost.has("stageSeq")).isTrue();
        assertThat(forHost.get("waitingCount").asInt()).isZero();
        assertThat(forHost.get("raisedHands").findValuesAsString("displayName")).containsExactly("Guest Gary");
        assertThat(forHost.get("localRecorders").findValuesAsString("participantId")).containsExactly(guestPid);
        assertThat(forHost.get("participants").findValuesAsString("displayName"))
                .containsExactlyInAnyOrder("State Host", "Guest Gary");

        JsonNode forGuest = state(meetingId, guestPid, guestSecret, null);
        assertThat(forGuest.get("role").asString()).isEqualTo("GUEST");
        assertThat(forGuest.has("stageSeq")).isFalse();
        assertThat(forGuest.has("participants")).isFalse();
        assertThat(forGuest.has("waitingCount")).isFalse();
        assertThat(forGuest.get("raisedHands").findValuesAsString("participantId")).containsExactly(guestPid);
    }

    @Test
    void webinarAttendeeSeesNoOtherAttendeesActivityInTheSnapshot() throws Exception {
        String meetingId = meeting("HOSTS_ONLY");
        join(meetingId, host);
        JsonNode a = guestJoin(meetingId, "Guest A");
        JsonNode b = guestJoin(meetingId, "Guest B");
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/signals/hand").header(TOKEN_HEADER, a.get("waitToken").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", a.get("participantId").asString(), "raised", true))))
                .andExpect(status().isNoContent());

        JsonNode forB = state(meetingId, b.get("participantId").asString(), b.get("waitToken").asString(), null);
        assertThat(forB.get("raisedHands").size()).isZero();
    }

    @Test
    void attendeeHandGoesToTheStageOnlyAndCarriesAStageSequenceNumber() throws Exception {
        String meetingId = meeting("HOSTS_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        JsonNode guest = guestJoin(meetingId, "Guest Hand");
        String guestPid = guest.get("participantId").asString();
        clearInvocations(media);

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/signals/hand")
                        .header(TOKEN_HEADER, guest.get("waitToken").asString()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", guestPid, "raised", true))))
                .andExpect(status().isNoContent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(media, times(2)).sendData(eq(roomName), eq("hand"), payload.capture(), to.capture());
        assertThat(to.getAllValues()).containsExactlyInAnyOrder(List.of(hostPid), List.of(guestPid));
        for (int i = 0; i < 2; i++) {
            JsonNode packet = json.readTree(new String(payload.getAllValues().get(i), StandardCharsets.UTF_8));
            assertThat(packet.get("raised").asBoolean()).isTrue();
            if (to.getAllValues().get(i).contains(hostPid)) {
                assertThat(packet.get("stream").asString()).isEqualTo("stage");
                assertThat(packet.get("seq").asLong()).isPositive();
            } else {
                // QA56-5: the attendee's own echo is unnumbered: stage numbers would reveal other attendees' activity.
                assertThat(packet.has("seq")).isFalse();
                assertThat(packet.has("stream")).isFalse();
            }
        }
    }

    @Test
    void broadcastsAreNumberedAndTheSnapshotReportsTheLatestNumber() throws Exception {
        String meetingId = meeting("EVERYONE");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        clearInvocations(media);
        for (boolean locked : new boolean[] {true, false}) {
            mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/lock").header("Authorization", host.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("locked", locked))))
                    .andExpect(status().isNoContent());
        }
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(media, atLeastOnce()).sendData(eq(roomName), eq("moderation"), payloads.capture(), eq(List.of()));
        List<Long> seqs = payloads.getAllValues().stream()
                .map(b -> json.readTree(new String(b, StandardCharsets.UTF_8)).get("seq").asLong()).toList();
        assertThat(seqs).hasSize(2);
        assertThat(seqs.get(1)).isEqualTo(seqs.get(0) + 1);
        assertThat(state(meetingId, hostPid, null, host).get("seq").asLong()).isEqualTo(seqs.get(1));
    }

    @Test
    void reactionsMustBeOnTheAllowList() throws Exception {
        String meetingId = meeting("EVERYONE");
        JsonNode hostJoin = join(meetingId, host);
        String hostPid = hostJoin.get("participantId").asString();
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/signals/reaction").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", hostPid, "emoji", "💩"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/signals/reaction").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", hostPid, "emoji", "👍"))))
                .andExpect(status().isNoContent());
        verify(media).sendData(eq(hostJoin.get("roomName").asString()), eq("reactions"), any(), eq(List.of()));
    }

    @Test
    void outsidersCannotReadRoomStateOrSignal() throws Exception {
        String meetingId = meeting("EVERYONE");
        String hostPid = join(meetingId, host).get("participantId").asString();
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/room-state").param("participantId", hostPid)
                        .header("Authorization", alice.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/room-state").param("participantId", hostPid)
                        .header(TOKEN_HEADER, "not-the-secret"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/signals/hand").header("Authorization", alice.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", hostPid, "raised", true))))
                .andExpect(status().isForbidden());
    }

    /** SYNC-1: a disconnect of an older connection arriving after the reconnect does not mark the participant gone. */
    @Test
    void lateDisconnectOfAnOlderConnectionIsIgnored() throws Exception {
        String meetingId = meeting("EVERYONE");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String pid = hostJoin.get("participantId").asString();
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, pid, "PA_old");
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, pid, "PA_new");
        webhook(MediaWebhookEvent.Type.PARTICIPANT_LEFT, roomName, pid, "PA_old");
        assertThat(participants.findById(UUID.fromString(pid)).orElseThrow().getState())
                .isEqualTo(MeetingParticipant.State.JOINED);

        webhook(MediaWebhookEvent.Type.PARTICIPANT_LEFT, roomName, pid, "PA_new");
        assertThat(participants.findById(UUID.fromString(pid)).orElseThrow().getState())
                .isEqualTo(MeetingParticipant.State.LEFT);
    }

    /** SYNC-3 / QA-B3: a host whose connection drops and who does not come back gets a successor after the grace. */
    @Test
    void hostDroppingOutPromotesTheEarliestSignedInParticipantAfterTheGrace() throws Exception {
        String meetingId = meeting("EVERYONE");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, hostPid, "PA_h");
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, alicePid, "PA_a");
        clearInvocations(media); // joining pushed each one's current rights

        webhook(MediaWebhookEvent.Type.PARTICIPANT_LEFT, roomName, hostPid, "PA_h");

        // Not at once: the host may only be reloading.
        assertThat(role(alicePid)).isEqualTo("PARTICIPANT");
        awaitRole(alicePid, "CO_HOST");
        verify(media).updateParticipant(eq(roomName), eq(alicePid), anyString(), any());
    }

    /** QA-B3: a host who reconnects within the grace (page reload) keeps the meeting; nobody is promoted. */
    @Test
    void hostReconnectingWithinTheGraceIsNotReplaced() throws Exception {
        String meetingId = meeting("EVERYONE");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, hostPid, "PA_h1");
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, alicePid, "PA_a");

        webhook(MediaWebhookEvent.Type.PARTICIPANT_LEFT, roomName, hostPid, "PA_h1");
        String rejoinedPid = join(meetingId, host).get("participantId").asString();
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, rejoinedPid, "PA_h2");

        Thread.sleep(1500);
        assertThat(role(alicePid)).isEqualTo("PARTICIPANT");
    }

    /** QA-B3: in a webinar nobody is promoted automatically (it would reveal an attendee to everyone). */
    @Test
    void webinarHostDroppingOutPromotesNobody() throws Exception {
        String meetingId = meeting("HOSTS_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, hostPid, "PA_h");
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, alicePid, "PA_a");

        webhook(MediaWebhookEvent.Type.PARTICIPANT_LEFT, roomName, hostPid, "PA_h");
        Thread.sleep(1500);
        assertThat(role(alicePid)).isEqualTo("PARTICIPANT");
        verify(media, org.mockito.Mockito.never()).renameParticipant(anyString(), eq(alicePid), anyString());

        // Leaving through the app does not promote anyone in a webinar either.
        String hostAgain = join(meetingId, host).get("participantId").asString();
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/leave").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("participantId", hostAgain))))
                .andExpect(status().isNoContent());
        assertThat(role(alicePid)).isEqualTo("PARTICIPANT");
    }

    /** QA-B5: leaving through the app, or being removed, also clears hand and local recording for everyone. */
    @Test
    void leavingOrBeingRemovedClearsHandAndLocalRecordingForEveryone() throws Exception {
        String meetingId = meeting("EVERYONE");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        JsonNode leaver = guestJoin(meetingId, "Guest Leaver");
        JsonNode removed = guestJoin(meetingId, "Guest Removed");
        raiseHand(meetingId, leaver);
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/local-recordings")
                        .header(TOKEN_HEADER, removed.get("waitToken").asString()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", removed.get("participantId").asString(), "state", "STARTED"))))
                .andExpect(status().isNoContent());
        clearInvocations(media);

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/leave").header(TOKEN_HEADER, leaver.get("waitToken").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", leaver.get("participantId").asString()))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/"
                        + removed.get("participantId").asString() + "/remove").header("Authorization", host.bearer()))
                .andExpect(status().isNoContent());

        ArgumentCaptor<byte[]> hands = ArgumentCaptor.forClass(byte[].class);
        verify(media).sendData(eq(roomName), eq("hand"), hands.capture(), anyList());
        JsonNode lowered = json.readTree(new String(hands.getValue(), StandardCharsets.UTF_8));
        assertThat(lowered.get("participantId").asString()).isEqualTo(leaver.get("participantId").asString());
        assertThat(lowered.get("raised").asBoolean()).isFalse();
        ArgumentCaptor<byte[]> recordings = ArgumentCaptor.forClass(byte[].class);
        verify(media).sendData(eq(roomName), eq("recording"), recordings.capture(), anyList());
        JsonNode stopped = json.readTree(new String(recordings.getValue(), StandardCharsets.UTF_8));
        assertThat(stopped.get("participantId").asString()).isEqualTo(removed.get("participantId").asString());
        assertThat(stopped.get("state").asString()).isEqualTo("STOPPED");
    }

    /** QA-B2: a consent notice goes out at once unnumbered, then numbered after commit (so snapshots match it). */
    @Test
    void consentNoticeIsSentUnnumberedNowAndNumberedAfterCommit() throws Exception {
        String meetingId = meeting("EVERYONE");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        JsonNode guest = guestJoin(meetingId, "Guest Consent");
        clearInvocations(media);
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/local-recordings")
                        .header(TOKEN_HEADER, guest.get("waitToken").asString()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", guest.get("participantId").asString(), "state", "STARTED"))))
                .andExpect(status().isNoContent());

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(media, org.mockito.Mockito.times(2)).sendData(eq(roomName), eq("recording"), payloads.capture(), anyList());
        JsonNode first = json.readTree(new String(payloads.getAllValues().get(0), StandardCharsets.UTF_8));
        JsonNode second = json.readTree(new String(payloads.getAllValues().get(1), StandardCharsets.UTF_8));
        assertThat(first.has("seq")).isFalse();
        assertThat(second.get("seq").asLong()).isPositive();
        assertThat(second.get("epoch").asString()).isNotBlank();
        JsonNode snapshot = state(meetingId, hostJoin.get("participantId").asString(), null, host);
        assertThat(snapshot.get("seq").asLong()).isEqualTo(second.get("seq").asLong());
        assertThat(snapshot.get("epoch").asString()).isEqualTo(second.get("epoch").asString());
        assertThat(snapshot.get("localRecorders").size()).isEqualTo(1);
    }

    /** PRIV-3: a recorder or hand-raiser who drops out is cleared for everyone. */
    @Test
    void droppingOutClearsHandAndLocalRecordingForEveryone() throws Exception {
        String meetingId = meeting("EVERYONE");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        JsonNode guest = guestJoin(meetingId, "Guest Rec");
        String guestPid = guest.get("participantId").asString();
        String secret = guest.get("waitToken").asString();
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, guestPid, "PA_g");
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/local-recordings").header(TOKEN_HEADER, secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", guestPid, "state", "STARTED"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/signals/hand").header(TOKEN_HEADER, secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", guestPid, "raised", true))))
                .andExpect(status().isNoContent());
        clearInvocations(media);

        webhook(MediaWebhookEvent.Type.PARTICIPANT_LEFT, roomName, guestPid, "PA_g");

        ArgumentCaptor<byte[]> recording = ArgumentCaptor.forClass(byte[].class);
        verify(media).sendData(eq(roomName), eq("recording"), recording.capture(), anyList());
        JsonNode stopped = json.readTree(new String(recording.getValue(), StandardCharsets.UTF_8));
        assertThat(stopped.get("kind").asString()).isEqualTo("LOCAL");
        assertThat(stopped.get("state").asString()).isEqualTo("STOPPED");
        assertThat(stopped.get("participantId").asString()).isEqualTo(guestPid);
        verify(media).sendData(eq(roomName), eq("hand"), any(), anyList());

        JsonNode forHost = state(meetingId, hostPid, null, host);
        assertThat(forHost.get("localRecorders").size()).isZero();
        assertThat(forHost.get("raisedHands").size()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String meeting(String visibility) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "State " + visibility, "type", "INSTANT",
                "timeZone", "UTC", "visibilityMode", visibility));
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ADMITTED"))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode state(String meetingId, String participantId, String secret, SignedIn user) throws Exception {
        var request = get("/api/v1/meetings/" + meetingId + "/room-state").param("participantId", participantId);
        if (secret != null) {
            request.header(TOKEN_HEADER, secret);
        }
        if (user != null) {
            request.header("Authorization", user.bearer());
        }
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private String role(String participantId) {
        return participants.findById(UUID.fromString(participantId)).orElseThrow().getRole().name();
    }

    private void awaitRole(String participantId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!expected.equals(role(participantId)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(role(participantId)).isEqualTo(expected);
    }

    private void raiseHand(String meetingId, JsonNode participant) throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/signals/hand")
                        .header(TOKEN_HEADER, participant.get("waitToken").asString()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", participant.get("participantId").asString(), "raised", true))))
                .andExpect(status().isNoContent());
    }

    private void webhook(MediaWebhookEvent.Type type, String roomName, String identity, String sid) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_" + UUID.randomUUID(),
                type, type.name().toLowerCase(), roomName, identity, null, Instant.now(), sid));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }
}
