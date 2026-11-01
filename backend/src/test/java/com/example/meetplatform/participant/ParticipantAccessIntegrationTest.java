package com.example.meetplatform.participant;

import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who keeps access to a meeting after the join flow (admitted vs. waiting / denied / removed), session-scoped
 * co-host rights, capacity rules for moderators, and re-checks on media reconnects.
 */
class ParticipantAccessIntegrationTest extends AbstractIntegrationTest {

    private static final String TOKEN_HEADER = "X-Participant-Token";

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void media() {
        when(media.issueToken(any())).thenReturn("signed-livekit-token");
        when(media.clientUrl()).thenReturn("wss://media.example.test");
    }

    @Test
    void waitingDeniedAndRemovedParticipantsGetNoLastingAccess() throws Exception {
        SignedIn host = users.create(mvc, "Gate Host");
        SignedIn denied = users.create(mvc, "Denied User");
        SignedIn removed = users.create(mvc, "Removed User");
        String meetingId = createMeeting(host, Map.of("title", "Gated", "type", "INSTANT", "timeZone", "UTC",
                "waitingRoomEnabled", true, "allowGuests", true));
        String hostPid = join(meetingId, host).get("participantId").asString();

        String deniedPid = join(meetingId, denied).get("participantId").asString();
        JsonNode deniedGuest = joinGuest(meetingId, "Denied Guest");
        JsonNode waitingGuest = joinGuest(meetingId, "Waiting Guest");
        JsonNode admittedGuest = joinGuest(meetingId, "Admitted Guest");
        String removedPid = join(meetingId, removed).get("participantId").asString();

        moderate(host, meetingId, "waiting/" + deniedPid + "/deny");
        moderate(host, meetingId, "waiting/" + deniedGuest.get("participantId").asString() + "/deny");
        moderate(host, meetingId, "waiting/" + admittedGuest.get("participantId").asString() + "/admit");
        moderate(host, meetingId, "waiting/" + removedPid + "/admit");
        // While admitted, a participant may read the meeting...
        mvc.perform(get("/api/v1/meetings/" + meetingId).header("Authorization", removed.bearer()))
                .andExpect(status().isOk());
        // ...but removal revokes that.
        moderate(host, meetingId, "participants/" + removedPid + "/remove");
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/chat").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", hostPid, "text", "posted after the denials"))))
                .andExpect(status().isCreated());

        for (SignedIn outsider : new SignedIn[] {denied, removed}) {
            mvc.perform(get("/api/v1/meetings/" + meetingId).header("Authorization", outsider.bearer()))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/meetings/" + meetingId + "/participants").header("Authorization", outsider.bearer()))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", outsider.bearer()))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/meetings").param("scope", "all").header("Authorization", outsider.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].id", not(hasItem(meetingId))));
        }

        // A denied or still-waiting guest's secret reads neither chat nor files...
        for (JsonNode guest : new JsonNode[] {deniedGuest, waitingGuest}) {
            String secret = guest.get("waitToken").asString();
            mvc.perform(get("/api/v1/meetings/" + meetingId + "/chat").header(TOKEN_HEADER, secret))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/meetings/" + meetingId + "/chat").header(TOKEN_HEADER, secret)
                            .param("participantId", guest.get("participantId").asString()))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/meetings/" + meetingId + "/files").header(TOKEN_HEADER, secret))
                    .andExpect(status().isForbidden());
        }
        // ...but a waiting guest can still poll its status and cancel its knock.
        String waitingPid = waitingGuest.get("participantId").asString();
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/join-status").param("participantId", waitingPid)
                        .header(TOKEN_HEADER, waitingGuest.get("waitToken").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING"));
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/leave").header(TOKEN_HEADER, waitingGuest.get("waitToken").asString())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("participantId", waitingPid))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/chat").header(TOKEN_HEADER, waitingGuest.get("waitToken").asString()))
                .andExpect(status().isForbidden());

        // An admitted guest keeps reading the meeting chat.
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/chat").header(TOKEN_HEADER, admittedGuest.get("waitToken").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].text", hasItem("posted after the denials")));
    }

    @Test
    void removedCoHostDoesNotInheritModeratorRightsInTheNextSession() throws Exception {
        SignedIn host = users.create(mvc, "Room Owner");
        SignedIn coHost = users.create(mvc, "Former CoHost");
        String roomId = json.readTree(mvc.perform(get("/api/v1/rooms/personal").header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asString();

        join(roomId, host);
        String coPid = join(roomId, coHost).get("participantId").asString();
        moderate(host, roomId, "waiting/" + coPid + "/admit");
        mvc.perform(post("/api/v1/meetings/" + roomId + "/moderation/participants/" + coPid + "/role")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("role", "CO_HOST"))))
                .andExpect(status().isNoContent());
        moderate(host, roomId, "participants/" + coPid + "/remove");
        mvc.perform(post("/api/v1/meetings/" + roomId + "/end").header("Authorization", host.bearer()))
                .andExpect(status().isNoContent());

        // Host absent: the former co-host opens the next session but is an ordinary participant in the waiting room.
        mvc.perform(post("/api/v1/meetings/" + roomId + "/join").header("Authorization", coHost.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.role").value("PARTICIPANT"))
                .andExpect(jsonPath("$.token").doesNotExist());
        mvc.perform(post("/api/v1/meetings/" + roomId + "/moderation/lock").header("Authorization", coHost.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("locked", true))))
                .andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("select locked from meetings where id = ?", Boolean.class,
                UUID.fromString(roomId))).isFalse();
    }

    @Test
    void coHostPromotionEndsWithTheSession() throws Exception {
        SignedIn host = users.create(mvc, "Session Host");
        SignedIn coHost = users.create(mvc, "One Session CoHost");
        // A personal room is reusable: ending a session leaves it joinable (waiting room on by default).
        String meetingId = json.readTree(mvc.perform(get("/api/v1/rooms/personal").header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asString();
        join(meetingId, host);
        String coPid = join(meetingId, coHost).get("participantId").asString();
        moderate(host, meetingId, "waiting/" + coPid + "/admit");
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + coPid + "/role")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("role", "CO_HOST"))))
                .andExpect(status().isNoContent());
        // Re-joining the same live session keeps the co-host role.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/leave").header("Authorization", coHost.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("participantId", coPid))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", coHost.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CO_HOST"))
                .andExpect(jsonPath("$.status").value("ADMITTED"));
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/end").header("Authorization", host.bearer()))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", coHost.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PARTICIPANT"))
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    void hostIsExemptFromCapacity() throws Exception {
        SignedIn host = users.create(mvc, "Late Host");
        String meetingId = createMeeting(host, Map.of("title", "1:1", "type", "ONE_TO_ONE", "timeZone", "UTC"));
        joinGuest(meetingId, "Early Guest A");
        joinGuest(meetingId, "Early Guest B");

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", host.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.role").value("HOST"));
        guestJoin(meetingId, "Third Guest")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEETING_FULL"));
    }

    @Test
    void sessionStartAdmitsWaitingPeopleOnlyUpToCapacity() throws Exception {
        SignedIn host = users.create(mvc, "Punctual Host");
        String meetingId = createMeeting(host, Map.of("title", "Small", "type", "INSTANT", "timeZone", "UTC",
                "maxParticipants", 2, "joinBeforeHost", false));
        JsonNode first = joinGuest(meetingId, "First");
        JsonNode second = joinGuest(meetingId, "Second");
        JsonNode third = joinGuest(meetingId, "Third");
        assertThat(third.get("status").asString()).isEqualTo("WAITING");

        join(meetingId, host);

        assertThat(state(first)).isEqualTo("ADMITTED");
        assertThat(state(second)).isEqualTo("ADMITTED");
        assertThat(state(third)).isEqualTo("WAITING");
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/join-status")
                        .param("participantId", third.get("participantId").asString())
                        .header(TOKEN_HEADER, third.get("waitToken").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.waitingReason").value("WAITING_ROOM"));
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/moderation/waiting").header("Authorization", host.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(third.get("participantId").asString())));
    }

    @Test
    void rejectedJoinDoesNotCreateAMediaRoom() throws Exception {
        SignedIn host = users.create(mvc, "Locking Host");
        String meetingId = createMeeting(host, Map.of("title", "Locked early", "type", "INSTANT", "timeZone", "UTC"));
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/lock").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("locked", true))))
                .andExpect(status().isNoContent());

        guestJoin(meetingId, "Too Early")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEETING_LOCKED"));
        verify(media, never()).createRoom(startsWith("mtg-" + meetingId), anyInt());
    }

    @Test
    void joinResponsesDoNotExposeTheHostEmail() throws Exception {
        SignedIn host = users.create(mvc, "Private Email Host");
        String meetingId = createMeeting(host, Map.of("title", "Open", "type", "INSTANT", "timeZone", "UTC"));
        guestJoin(meetingId, "Curious Guest")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meeting.host.displayName").value("Private Email Host"))
                .andExpect(jsonPath("$.meeting.host.email").doesNotExist());
        mvc.perform(get("/api/v1/meetings/" + meetingId).header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.host.email").value(host.email()));
    }

    @Test
    void reconnectAfterLeavingIsRecheckedAgainstTheLock() throws Exception {
        SignedIn host = users.create(mvc, "Reconnect Host");
        String meetingId = createMeeting(host, Map.of("title", "Reconnects", "type", "INSTANT", "timeZone", "UTC"));
        String roomName = join(meetingId, host).get("roomName").asString();
        JsonNode guest = joinGuest(meetingId, "Flaky Guest");
        String guestPid = guest.get("participantId").asString();
        assertThat(guest.get("status").asString()).isEqualTo("ADMITTED");

        webhook("EV_rc_join_1", MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, guestPid);
        webhook("EV_rc_left_1", MediaWebhookEvent.Type.PARTICIPANT_LEFT, roomName, guestPid);
        // Left longer ago than the reconnect window (within it, a reload keeps its seat: see PresenceIntegrationTest).
        jdbc.update("update meeting_participants set left_at = left_at - interval '1 hour' where id = ?",
                java.util.UUID.fromString(guestPid));
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/lock").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("locked", true))))
                .andExpect(status().isNoContent());

        // The still-valid media token is used to reconnect into the now locked room: ejected.
        webhook("EV_rc_join_2", MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, guestPid);
        verify(media).removeParticipant(roomName, guestPid);
        assertThat(state(guest)).isEqualTo("LEFT");

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/lock").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("locked", false))))
                .andExpect(status().isNoContent());
        webhook("EV_rc_join_3", MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, guestPid);
        assertThat(state(guest)).isEqualTo("JOINED");
    }

    // ------------------------------------------------------------------ helpers

    private String createMeeting(SignedIn host, Map<String, Object> body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(new HashMap<>(body))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private JsonNode join(String meetingId, SignedIn user) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions guestJoin(String meetingId, String name) throws Exception {
        return mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("displayName", name))));
    }

    private JsonNode joinGuest(String meetingId, String name) throws Exception {
        return json.readTree(guestJoin(meetingId, name).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private void moderate(SignedIn moderator, String meetingId, String action) throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/" + action)
                        .header("Authorization", moderator.bearer()))
                .andExpect(status().isNoContent());
    }

    private void webhook(String id, MediaWebhookEvent.Type type, String room, String identity) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(
                new MediaWebhookEvent(id, type, type.name().toLowerCase(), room, identity, null, Instant.now()));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }

    private String state(JsonNode joinResponse) {
        return jdbc.queryForObject("select state from meeting_participants where id = ?", String.class,
                UUID.fromString(joinResponse.get("participantId").asString()));
    }
}
