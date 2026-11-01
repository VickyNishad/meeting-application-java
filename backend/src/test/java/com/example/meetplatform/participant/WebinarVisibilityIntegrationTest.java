package com.example.meetplatform.participant;

import com.example.meetplatform.media.dto.MediaTokenRequest;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webinar visibility (HOST_AND_SELF_ONLY): attendees appear to the SFU as an anonymous "Attendee", nobody can publish data,
 * and what an attendee says or shares reaches only the stage. Normal meetings keep real names.
 */
class WebinarVisibilityIntegrationTest extends AbstractIntegrationTest {

    private static final String TOKEN_HEADER = "X-Participant-Token";

    @Autowired
    TestUsers users;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private SignedIn host;
    private SignedIn alice;
    private SignedIn bob;

    @BeforeEach
    void setUp() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        host = users.create(mvc, "Webinar Host");
        alice = users.create(mvc, "Alice Attendee");
        bob = users.create(mvc, "Bob Attendee");
    }

    /**
     * Phase 5 / QA56-1, HOST_AND_SELF_ONLY: the SFU keeps attendees apart. Stage room: the host publishes; attendees
     * only subscribe and are hidden. Every attendee publishes in an audience room of its own, where it cannot
     * subscribe; the host watches each of them there, hidden. No attendee ever shares a room with another visible
     * attendee, so the SFU cannot tell one about another.
     */
    @Test
    void hostAndSelfOnlyGivesEveryAttendeeAnAudienceRoomOfItsOwn() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        JsonNode aliceJoin = join(meetingId, alice);
        JsonNode bobJoin = join(meetingId, bob);
        String stageRoom = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        String alicePid = aliceJoin.get("participantId").asString();
        String bobPid = bobJoin.get("participantId").asString();
        assertThat(aliceJoin.get("audienceRoomName").asString()).isEqualTo(audience(stageRoom, alicePid));
        assertThat(bobJoin.get("audienceRoomName").asString()).isEqualTo(audience(stageRoom, bobPid));
        assertThat(hostJoin.hasNonNull("audienceRoomName")).isFalse();
        verify(media).createRoom(eq(stageRoom), anyInt());
        verify(media).createRoom(eq(audience(stageRoom, alicePid)), anyInt());
        verify(media).createRoom(eq(audience(stageRoom, bobPid)), anyInt());

        // The host gets credentials for every attendee's room; an attendee only for its own.
        JsonNode hostAccess = audienceAccess(meetingId, hostPid, host, null);
        assertThat(hostAccess.get("rooms").findValuesAsString("roomName"))
                .containsExactlyInAnyOrder(audience(stageRoom, alicePid), audience(stageRoom, bobPid));
        JsonNode aliceAccess = audienceAccess(meetingId, alicePid, alice, null);
        assertThat(aliceAccess.get("rooms").findValuesAsString("roomName"))
                .containsExactly(audience(stageRoom, alicePid));
        // Another meeting participant's id cannot be used to get its rooms.
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/audience-access").param("participantId", hostPid)
                .header("Authorization", alice.bearer())).andExpect(status().isForbidden());

        ArgumentCaptor<MediaTokenRequest> captured = ArgumentCaptor.forClass(MediaTokenRequest.class);
        verify(media, atLeastOnce()).issueToken(captured.capture());
        List<MediaTokenRequest> tokens = captured.getAllValues();
        MediaTokenRequest hostStage = token(tokens, hostPid, stageRoom);
        MediaTokenRequest hostWatchingAlice = token(tokens, hostPid, audience(stageRoom, alicePid));
        MediaTokenRequest aliceStage = token(tokens, alicePid, stageRoom);
        MediaTokenRequest aliceAudience = token(tokens, alicePid, audience(stageRoom, alicePid));

        // Host: publishes on the stage, visible; watches each attendee's room, hidden there and publishing nothing.
        assertThat(hostStage.permissions().canPublish()).isTrue();
        assertThat(hostStage.permissions().hidden()).isFalse();
        assertThat(hostStage.displayName()).isEqualTo("Webinar Host");
        assertThat(hostWatchingAlice.permissions().canSubscribe()).isTrue();
        assertThat(hostWatchingAlice.permissions().canPublish()).isFalse();
        assertThat(hostWatchingAlice.permissions().hidden()).isTrue();
        // Attendee: watches the stage without publishing and is invisible there...
        assertThat(aliceStage.permissions().canSubscribe()).isTrue();
        assertThat(aliceStage.permissions().canPublish()).isFalse();
        assertThat(aliceStage.permissions().hidden()).isTrue();
        // ...and publishes in its own audience room, where it cannot subscribe to anyone.
        assertThat(aliceAudience.permissions().canPublish()).isTrue();
        assertThat(aliceAudience.permissions().canSubscribe()).isFalse();
        // No attendee token for any room but the stage and its own.
        assertThat(tokens.stream().filter(t -> t.identity().equals(alicePid)).map(MediaTokenRequest::roomName))
                .allMatch(r -> r.equals(stageRoom) || r.equals(audience(stageRoom, alicePid)));
        // Nobody may publish data; attendees carry no name or user id other people could read.
        for (MediaTokenRequest t : tokens) {
            assertThat(t.permissions().canPublishData()).isFalse();
        }
        assertThat(aliceAudience.displayName()).isEqualTo("Attendee");
        assertThat(aliceAudience.metadata()).contains("\"PARTICIPANT\"").doesNotContain(alice.id().toString());
        assertThat(aliceStage.metadata()).doesNotContain(alice.id().toString());
    }

    /** QA56-1/2: only the attendee itself and the stage may be in an attendee's audience room. */
    @Test
    void anotherAttendeeInSomeonesAudienceRoomIsEjected() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String stageRoom = hostJoin.get("roomName").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        String bobPid = join(meetingId, bob).get("participantId").asString();
        joinedWebhook(stageRoom, alicePid);
        joinedWebhook(stageRoom, bobPid);
        clearInvocations(media);

        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, audience(stageRoom, alicePid), bobPid, "PA_b2");
        verify(media).removeParticipant(audience(stageRoom, alicePid), bobPid);

        // The owner and the host stay, with the rights of their current role pushed to the SFU.
        ArgumentCaptor<com.example.meetplatform.media.dto.MediaPermissions> perms =
                ArgumentCaptor.forClass(com.example.meetplatform.media.dto.MediaPermissions.class);
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, audience(stageRoom, alicePid), alicePid, "PA_a2");
        verify(media).updateParticipant(eq(audience(stageRoom, alicePid)), eq(alicePid), anyString(), perms.capture());
        assertThat(perms.getValue().canSubscribe()).isFalse();
        String hostPid = hostJoin.get("participantId").asString();
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, audience(stageRoom, alicePid), hostPid, "PA_h2");
        verify(media).updateParticipant(eq(audience(stageRoom, alicePid)), eq(hostPid), anyString(), perms.capture());
        assertThat(perms.getValue().hidden()).isTrue();
        verify(media, never()).removeParticipant(audience(stageRoom, alicePid), alicePid);
        verify(media, never()).removeParticipant(audience(stageRoom, alicePid), hostPid);
    }

    /**
     * QA56-2: a co-host demoted before connecting still holds tokens with co-host rights. Connecting with them, it
     * is thrown out of attendee rooms and gets attendee rights pushed on the stage.
     */
    @Test
    void demotedCoHostConnectingWithAnOldTokenGetsItsCurrentRights() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String stageRoom = hostJoin.get("roomName").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        String bobPid = join(meetingId, bob).get("participantId").asString();
        joinedWebhook(stageRoom, alicePid);
        role(meetingId, alicePid, "CO_HOST");
        role(meetingId, alicePid, "PARTICIPANT");
        clearInvocations(media);

        // Alice reconnects with the tokens she got as co-host (still within their TTL).
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, audience(stageRoom, bobPid), alicePid, "PA_old");
        verify(media).removeParticipant(audience(stageRoom, bobPid), alicePid);
        webhook(MediaWebhookEvent.Type.PARTICIPANT_JOINED, stageRoom, alicePid, "PA_old_stage");
        ArgumentCaptor<com.example.meetplatform.media.dto.MediaPermissions> perms =
                ArgumentCaptor.forClass(com.example.meetplatform.media.dto.MediaPermissions.class);
        verify(media).updateParticipant(eq(stageRoom), eq(alicePid), anyString(), perms.capture());
        assertThat(perms.getValue().canPublish()).isFalse();
        assertThat(perms.getValue().hidden()).isTrue();
    }

    /**
     * QA56-16: a demotion is told to the stage and the person only (attendees must not learn that this person is
     * still here); the demoted co-host is disconnected from every attendee room it was watching.
     */
    @Test
    void demotionIsNotAnnouncedToAttendeesAndEndsWatching() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String stageRoom = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        joinedWebhook(stageRoom, hostPid);
        String alicePid = join(meetingId, alice).get("participantId").asString();
        String bobPid = join(meetingId, bob).get("participantId").asString();
        joinedWebhook(stageRoom, alicePid);
        joinedWebhook(stageRoom, bobPid);
        role(meetingId, alicePid, "CO_HOST");
        clearInvocations(media);

        role(meetingId, alicePid, "PARTICIPANT");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
        verify(media, atLeastOnce()).sendData(eq(stageRoom), eq("moderation"), any(), to.capture());
        assertThat(to.getAllValues()).allSatisfy(ids -> assertThat(ids).isNotEmpty().doesNotContain(bobPid));
        verify(media).removeParticipant(audience(stageRoom, bobPid), alicePid);
    }

    /** QA56-9: when the stage changes, attendees get their own count (stage + themselves), unnumbered. */
    @Test
    void attendeesLearnTheirCountWhenTheStageChangesButNotWhenAttendeesComeAndGo() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String stageRoom = hostJoin.get("roomName").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        joinedWebhook(stageRoom, alicePid);
        clearInvocations(media);

        joinedWebhook(stageRoom, hostJoin.get("participantId").asString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<byte[]> bodies = ArgumentCaptor.forClass(byte[].class);
        verify(media, atLeastOnce()).sendData(eq(stageRoom), eq("presence"), bodies.capture(), to.capture());
        int i = to.getAllValues().indexOf(List.of(alicePid));
        assertThat(i).isNotNegative();
        JsonNode forAlice = json.readTree(new String(bodies.getAllValues().get(i), StandardCharsets.UTF_8));
        assertThat(forAlice.get("count").asInt()).isEqualTo(2);
        assertThat(forAlice.has("seq")).isFalse();

        clearInvocations(media);
        String bobPid = join(meetingId, bob).get("participantId").asString();
        joinedWebhook(stageRoom, bobPid);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> later = ArgumentCaptor.forClass(List.class);
        verify(media, atLeastOnce()).sendData(eq(stageRoom), eq("presence"), any(), later.capture());
        assertThat(later.getAllValues()).noneSatisfy(ids -> assertThat(ids).contains(alicePid));
    }

    /** QA56-13: a session that fails to start after its media room was created does not leave the room behind. */
    @Test
    void aFailedSessionStartDeletesTheMediaRoomItCreated() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Early", "type", "INSTANT", "timeZone", "UTC",
                "visibilityMode", "HOST_AND_SELF_ONLY", "joinBeforeHost", true));
        String meetingId = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        // The stage room is created; the attendee's own audience room cannot be.
        org.mockito.Mockito.doNothing().doThrow(new com.example.meetplatform.common.exception.ApiException(
                        com.example.meetplatform.common.exception.ErrorCode.MEDIA_PROVIDER_UNAVAILABLE, "down"))
                .when(media).createRoom(anyString(), anyInt());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", alice.bearer()))
                .andExpect(status().is5xxServerError());
        ArgumentCaptor<String> created = ArgumentCaptor.forClass(String.class);
        verify(media, atLeastOnce()).createRoom(created.capture(), anyInt());
        verify(media).deleteRoom(created.getAllValues().get(0));
    }

    /** QA56-14: joining again (another device) returns the role this participation has now, as its tokens do. */
    @Test
    void joiningAgainReturnsTheRoleOfTheParticipation() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String alicePid = join(meetingId, alice).get("participantId").asString();
        joinedWebhook(hostJoin.get("roomName").asString(), alicePid);
        role(meetingId, alicePid, "CO_HOST");
        JsonNode again = join(meetingId, alice);
        assertThat(again.get("participantId").asString()).isEqualTo(alicePid);
        assertThat(again.get("role").asString()).isEqualTo("CO_HOST");
        assertThat(again.hasNonNull("audienceRoomName")).isFalse();
    }

    /** QA56-6: a co-host role in an earlier session does not open the stage-only chat of a later one. */
    @Test
    void aFormerCoHostRowDoesNotOpenStageOnlyChatHistory() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String oldAlicePid = join(meetingId, alice).get("participantId").asString();
        joinedWebhook(hostJoin.get("roomName").asString(), oldAlicePid);
        role(meetingId, oldAlicePid, "CO_HOST");
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/end").header("Authorization", host.bearer()))
                .andExpect(status().is2xxSuccessful());
        // A second session of the same meeting (as a recurring meeting or personal room would have; set directly).
        jdbc.update("UPDATE meetings SET status = 'SCHEDULED' WHERE id = ?", UUID.fromString(meetingId));

        join(meetingId, host);
        String newAlicePid = join(meetingId, alice).get("participantId").asString();
        assertThat(newAlicePid).isNotEqualTo(oldAlicePid);
        String bobPid = join(meetingId, bob).get("participantId").asString();
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/chat").header("Authorization", bob.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", bobPid, "text", "bob asks privately"))))
                .andExpect(status().isCreated());

        JsonNode viaOldRow = json.readTree(mvc.perform(get("/api/v1/meetings/" + meetingId + "/chat")
                        .param("participantId", oldAlicePid).header("Authorization", alice.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(viaOldRow.findValuesAsString("text")).doesNotContain("bob asks privately");
        assertThat(chat(meetingId, host, null).findValuesAsString("text")).contains("bob asks privately");
    }

    /** The old names are still accepted on input; responses use the new ones. */
    @Test
    void oldVisibilityNamesAreStillAccepted() throws Exception {
        String meetingId = meeting("HOSTS_ONLY");
        mvc.perform(get("/api/v1/meetings/" + meetingId).header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibilityMode").value("HOST_AND_SELF_ONLY"));
        String other = meeting("EVERYONE");
        mvc.perform(get("/api/v1/meetings/" + other).header("Authorization", host.bearer()))
                .andExpect(jsonPath("$.visibilityMode").value("EVERYONE_VISIBLE"));
    }

    /** Removing an attendee disconnects them from the stage and its own audience room; muting acts where they publish. */
    @Test
    void moderationActsOnTheRoomWhereTheAttendeePublishes() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String stageRoom = hostJoin.get("roomName").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        joinedWebhook(stageRoom, alicePid);
        when(media.muteTrack(anyString(), anyString(), any())).thenReturn(true);

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + alicePid + "/mute")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("source", "MICROPHONE"))))
                .andExpect(status().is2xxSuccessful());
        verify(media).muteTrack(eq(audience(stageRoom, alicePid)), eq(alicePid), any());

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + alicePid + "/remove")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isNoContent());
        verify(media).removeParticipant(stageRoom, alicePid);
        verify(media).removeParticipant(audience(stageRoom, alicePid), alicePid);
    }

    /** A removed attendee whose old token still works is thrown out of the audience room as well. */
    @Test
    void removedAttendeeReconnectingToTheAudienceRoomIsEjected() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String stageRoom = hostJoin.get("roomName").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + alicePid + "/remove")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isNoContent());
        clearInvocations(media);
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_" + UUID.randomUUID(),
                MediaWebhookEvent.Type.PARTICIPANT_JOINED, "participant_joined", audience(stageRoom, alicePid), alicePid,
                null, Instant.now(), "PA_x"));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
        verify(media).removeParticipant(audience(stageRoom, alicePid), alicePid);
    }

    /** Audience-room connections are not presence: only the stage room decides who is in the session. */
    @Test
    void audienceRoomEventsDoNotChangePresence() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String stageRoom = hostJoin.get("roomName").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        joinedWebhook(stageRoom, alicePid);
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_" + UUID.randomUUID(),
                MediaWebhookEvent.Type.PARTICIPANT_LEFT, "participant_left", audience(stageRoom, alicePid), alicePid,
                null, Instant.now(), "PA_aud"));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
        assertThat(participants(meetingId, host).findValuesAsString("state")).contains("JOINED");
    }

    private static MediaTokenRequest token(List<MediaTokenRequest> tokens, String identity, String room) {
        return tokens.stream().filter(t -> t.identity().equals(identity) && t.roomName().equals(room)).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no token for " + identity + " in " + room));
    }

    @Test
    void normalMeetingsKeepRealNamesButStillNoDataPublishing() throws Exception {
        String meetingId = meeting("EVERYONE");
        join(meetingId, host);
        join(meetingId, alice);
        ArgumentCaptor<MediaTokenRequest> tokens = ArgumentCaptor.forClass(MediaTokenRequest.class);
        verify(media, atLeastOnce()).issueToken(tokens.capture());
        MediaTokenRequest aliceToken = tokens.getAllValues().get(tokens.getAllValues().size() - 1);
        assertThat(aliceToken.displayName()).isEqualTo("Alice Attendee");
        assertThat(aliceToken.metadata()).contains(alice.id().toString());
        assertThat(aliceToken.permissions().canPublishData()).isFalse();
    }

    @Test
    void attendeesSeeOnlyTheStageInTheParticipantListWhileTheHostSeesEveryone() throws Exception {
        String meetingId = meeting("HOSTS_ONLY");
        join(meetingId, host);
        join(meetingId, alice);
        join(meetingId, bob);

        JsonNode forHost = participants(meetingId, host);
        assertThat(forHost.findValuesAsString("displayName"))
                .contains("Webinar Host", "Alice Attendee", "Bob Attendee");
        JsonNode forAlice = participants(meetingId, alice);
        assertThat(forAlice.findValuesAsString("displayName"))
                .containsExactlyInAnyOrder("Webinar Host", "Alice Attendee")
                .doesNotContain("Bob Attendee");
    }

    @Test
    void attendeeChatReachesOnlyTheStageAndNeverOtherAttendees() throws Exception {
        String meetingId = meeting("HOSTS_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        JsonNode aliceJoin = join(meetingId, alice);
        String alicePid = aliceJoin.get("participantId").asString();
        JsonNode bobJoin = join(meetingId, bob);
        clearInvocations(media);

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/chat").header("Authorization", alice.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", alicePid, "text", "question from alice"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.audience").value("STAGE"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<byte[]> bodies = ArgumentCaptor.forClass(byte[].class);
        verify(media, org.mockito.Mockito.times(2)).sendData(eq(roomName), eq("chat"), bodies.capture(), to.capture());
        assertThat(to.getAllValues()).containsExactlyInAnyOrder(List.of(hostPid), List.of(alicePid));
        // QA56-5: only the stage copy is numbered; the attendee echo carries no stage number.
        for (int i = 0; i < 2; i++) {
            JsonNode body = json.readTree(new String(bodies.getAllValues().get(i), StandardCharsets.UTF_8));
            assertThat(body.has("seq")).isEqualTo(to.getAllValues().get(i).contains(hostPid));
        }

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/chat").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", hostPid, "text", "answer for everyone"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.audience").value("ALL"));

        assertThat(chat(meetingId, host, null).findValuesAsString("text"))
                .contains("question from alice", "answer for everyone");
        assertThat(chat(meetingId, alice, null).findValuesAsString("text"))
                .contains("question from alice", "answer for everyone");
        assertThat(chat(meetingId, null, bobJoin.get("waitToken").asString()).findValuesAsString("text"))
                .containsExactly("answer for everyone");
        assertThat(chat(meetingId, bob, null).findValuesAsString("text"))
                .containsExactly("answer for everyone");
    }

    /** QA-B4: what was stage-only in a webinar stays so after the meeting stops being a webinar. */
    @Test
    void webinarMessagesAndAttendeesStayHiddenAfterTheModeChanges() throws Exception {
        String meetingId = meeting("HOSTS_ONLY");
        join(meetingId, host);
        String alicePid = join(meetingId, alice).get("participantId").asString();
        join(meetingId, bob);
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/chat").header("Authorization", alice.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", alicePid, "text", "private question"))))
                .andExpect(status().isCreated());

        // The meeting stops being a webinar (between sessions; written directly to skip ending the session here).
        jdbc.update("UPDATE meetings SET visibility_mode = 'EVERYONE_VISIBLE' WHERE id = ?", UUID.fromString(meetingId));

        assertThat(chat(meetingId, bob, null).findValuesAsString("text")).doesNotContain("private question");
        assertThat(participants(meetingId, bob).findValuesAsString("displayName")).doesNotContain("Alice Attendee");
        assertThat(chat(meetingId, host, null).findValuesAsString("text")).contains("private question");
        assertThat(participants(meetingId, host).findValuesAsString("displayName")).contains("Alice Attendee");
    }

    @Test
    void onlyTheStageSharesFilesInAWebinar() throws Exception {
        String meetingId = meeting("HOSTS_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String alicePid = join(meetingId, alice).get("participantId").asString();
        MockMultipartFile txt = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/meetings/" + meetingId + "/files").file(txt)
                        .param("participantId", alicePid).header("Authorization", alice.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/meetings/" + meetingId + "/files").file(txt)
                        .param("participantId", hostJoin.get("participantId").asString())
                        .header("Authorization", host.bearer()))
                .andExpect(status().isCreated());
    }

    @Test
    void visibilityCannotChangeWhileLiveButCanBetweenSessions() throws Exception {
        String meetingId = meeting("EVERYONE");
        mvc.perform(patch("/api/v1/meetings/" + meetingId).header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("visibilityMode", "HOSTS_ONLY"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibilityMode").value("HOST_AND_SELF_ONLY"));
        join(meetingId, host);
        mvc.perform(patch("/api/v1/meetings/" + meetingId).header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("visibilityMode", "EVERYONE"))))
                .andExpect(status().isConflict());
        // Re-sending the current value while live is not a change.
        mvc.perform(patch("/api/v1/meetings/" + meetingId).header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("visibilityMode", "HOSTS_ONLY"))))
                .andExpect(status().isOk());
    }

    /**
     * Promoting an attendee changes its rights in both rooms at once (the server pushes them to the SFU); its client
     * then reconnects with fresh tokens, which also makes it visible on the stage. Demoting reverses it immediately.
     */
    @Test
    void promotionAndDemotionUpdateRightsInBothRooms() throws Exception {
        String meetingId = meeting("HOST_AND_SELF_ONLY");
        JsonNode hostJoin = join(meetingId, host);
        String stageRoom = hostJoin.get("roomName").asString();
        String alicePid = join(meetingId, alice).get("participantId").asString();
        joinedWebhook(stageRoom, alicePid);
        clearInvocations(media);

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + alicePid + "/role")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("role", "CO_HOST"))))
                .andExpect(status().isNoContent());
        ArgumentCaptor<com.example.meetplatform.media.dto.MediaPermissions> perms =
                ArgumentCaptor.forClass(com.example.meetplatform.media.dto.MediaPermissions.class);
        verify(media).updateParticipant(eq(stageRoom), eq(alicePid), anyString(), perms.capture());
        assertThat(perms.getValue().canPublish()).isTrue();
        verify(media).updateParticipant(eq(audience(stageRoom, alicePid)), eq(alicePid), anyString(), perms.capture());
        assertThat(perms.getValue().canSubscribe()).isTrue();
        assertThat(perms.getValue().canPublish()).isFalse();
        clearInvocations(media);

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + alicePid + "/role")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("role", "PARTICIPANT"))))
                .andExpect(status().isNoContent());
        // Demoted: may no longer publish on the stage nor watch the audience room.
        verify(media).updateParticipant(eq(stageRoom), eq(alicePid), anyString(), perms.capture());
        assertThat(perms.getValue().canPublish()).isFalse();
        verify(media).updateParticipant(eq(audience(stageRoom, alicePid)), eq(alicePid), anyString(), perms.capture());
        assertThat(perms.getValue().canSubscribe()).isFalse();
    }


    // ------------------------------------------------------------------ helpers

    private static String audience(String stageRoom, String attendeeId) {
        return stageRoom + "-aud-" + attendeeId;
    }

    private String meeting(String visibility) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Visibility " + visibility, "type", "INSTANT",
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

    private JsonNode participants(String meetingId, SignedIn user) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/meetings/" + meetingId + "/participants")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode chat(String meetingId, SignedIn user, String participantSecret) throws Exception {
        var request = get("/api/v1/meetings/" + meetingId + "/chat");
        if (user != null) {
            request.header("Authorization", user.bearer());
        }
        if (participantSecret != null) {
            request.header(TOKEN_HEADER, participantSecret);
        }
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private JsonNode audienceAccess(String meetingId, String participantId, SignedIn user, String secret)
            throws Exception {
        var request = get("/api/v1/meetings/" + meetingId + "/audience-access").param("participantId", participantId);
        if (user != null) {
            request.header("Authorization", user.bearer());
        }
        if (secret != null) {
            request.header(TOKEN_HEADER, secret);
        }
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private void role(String meetingId, String participantId, String role) throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + participantId + "/role")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("role", role))))
                .andExpect(status().isNoContent());
    }

    private void webhook(MediaWebhookEvent.Type type, String roomName, String identity, String sid) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_" + UUID.randomUUID(),
                type, type.name().toLowerCase(), roomName, identity, null, Instant.now(), sid));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }

    private void joinedWebhook(String roomName, String participantId) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_" + UUID.randomUUID(),
                MediaWebhookEvent.Type.PARTICIPANT_JOINED, "participant_joined", roomName, participantId, null,
                Instant.now(), "PA_1"));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }
}
