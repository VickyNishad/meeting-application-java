package com.example.meetplatform.meeting;

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
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The vertical slice: create -> secure join (two parties) -> waiting room -> media presence via webhooks ->
 * host controls -> end -> persisted history. The SFU is mocked; its interactions are verified.
 */
class MeetingLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void media() {
        when(media.issueToken(any())).thenReturn("signed-livekit-token");
        when(media.clientUrl()).thenReturn("wss://media.example.test");
        when(media.muteTrack(anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    void fullMeetingLifecycleWithWaitingRoomAndHostControls() throws Exception {
        SignedIn host = users.create(mvc, "Host");
        SignedIn stranger = users.create(mvc, "Stranger");

        JsonNode meeting = json(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                                "title", "Design review", "type", "INSTANT", "timeZone", "Europe/London",
                                "waitingRoomEnabled", true, "allowGuests", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.myRole").value("HOST"))
                .andReturn().getResponse().getContentAsString());
        String meetingId = meeting.get("id").asString();
        String joinCode = meeting.get("joinCode").asString();

        // Public join summary by code does not require authentication.
        mvc.perform(get("/api/v1/meetings/by-code/" + joinCode)).andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingRoomEnabled").value(true));

        // Host joins: room is created on the SFU and a token is issued.
        JsonNode hostJoin = json(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.role").value("HOST"))
                .andExpect(jsonPath("$.token").value("signed-livekit-token"))
                .andReturn().getResponse().getContentAsString());
        verify(media).createRoom(anyString(), eq(100));
        String roomName = hostJoin.get("roomName").asString();
        String hostParticipant = hostJoin.get("participantId").asString();

        // A guest lands in the waiting room without a media token.
        JsonNode guestJoin = json(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("displayName", "Guest Gina"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn().getResponse().getContentAsString());
        String guestId = guestJoin.get("participantId").asString();
        String guestSecret = guestJoin.get("waitToken").asString();

        // A non-moderator cannot admit people.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/waiting/" + guestId + "/admit")
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());

        // The guest cannot poll someone else's status without the secret.
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/join-status").param("participantId", guestId)
                        .header("X-Participant-Token", "wrong"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/waiting/" + guestId + "/admit")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/join-status").param("participantId", guestId)
                        .header("X-Participant-Token", guestSecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.role").value("GUEST"))
                .andExpect(jsonPath("$.token").value("signed-livekit-token"));

        // Presence is driven by verified SFU webhooks (duplicate deliveries are ignored).
        deliverWebhook("EV_join_host", MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, hostParticipant);
        deliverWebhook("EV_join_guest", MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, guestId);
        deliverWebhook("EV_join_guest", MediaWebhookEvent.Type.PARTICIPANT_JOINED, roomName, guestId);
        assertThat(jdbc.queryForObject("select count(*) from webhook_events where event_id = 'EV_join_guest'", Long.class))
                .isEqualTo(1L);
        assertThat(state(guestId)).isEqualTo("JOINED");

        // Host controls are applied through the SFU admin API.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + guestId + "/mute")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("source", "MICROPHONE"))))
                .andExpect(status().isNoContent());
        verify(media).muteTrack(roomName, guestId, TrackSource.MICROPHONE);

        // There is no server-side unmute: only a request is sent to the participant.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + guestId + "/request-unmute")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("source", "MICROPHONE"))))
                .andExpect(status().isNoContent());

        // Guests cannot be promoted, and only moderators may lock the room.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + guestId + "/role")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("role", "CO_HOST"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/lock")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("locked", true))))
                .andExpect(status().isNoContent());

        // A locked room rejects new (non-moderator) joins.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", stranger.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEETING_LOCKED"));

        // Guests cannot end the meeting; the host can.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/end").header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/end").header("Authorization", host.bearer()))
                .andExpect(status().isNoContent());
        verify(media, times(1)).deleteRoom(roomName);

        // History is persisted.
        mvc.perform(get("/api/v1/meetings/" + meetingId).header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"));
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/participants").header("Authorization", host.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].state").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("LEFT"))));
        mvc.perform(get("/api/v1/meetings").param("scope", "past").header("Authorization", host.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + meetingId + "')]").exists());
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/moderation/audit").header("Authorization", host.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].action").value(org.hamcrest.Matchers.hasItems(
                        "MOD_ADMIT", "MOD_MUTE", "MOD_REQUEST_UNMUTE", "MOD_LOCK", "MEETING_ENDED")));

        // Ended one-off meetings cannot be joined again.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", host.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_ENDED"));
    }

    @Test
    void privateMeetingsRejectUninvitedUsersAndGuests() throws Exception {
        SignedIn host = users.create(mvc, "Private Host");
        SignedIn invitee = users.create(mvc, "Invitee");
        SignedIn outsider = users.create(mvc, "Outsider");

        String meetingId = json(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                                "title", "Board meeting", "type", "SCHEDULED", "timeZone", "UTC",
                                "startTime", Instant.now().plusSeconds(3600).toString(), "privacy", "INVITEES_ONLY",
                                "password", "s3cret", "inviteeEmails", List.of(invitee.email())))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();

        mvc.perform(get("/api/v1/meetings/" + meetingId).header("Authorization", outsider.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", outsider.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("password", "s3cret"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_INVITED"));
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("displayName", "Anon"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUESTS_NOT_ALLOWED"));

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", invitee.bearer()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MEETING_PASSWORD_REQUIRED"));
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", invitee.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("password", "nope"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEETING_PASSWORD_INVALID"));
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", invitee.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("password", "s3cret"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ADMITTED"));

        // An invitee is not a moderator.
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/mute-all").header("Authorization", invitee.bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void capacityIsEnforced() throws Exception {
        SignedIn host = users.create(mvc, "Duo Host");
        String meetingId = json(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                                "title", "1:1", "type", "ONE_TO_ONE", "timeZone", "UTC"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.maxParticipants").value(2))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", host.bearer()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("displayName", "First")))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("displayName", "Second"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEETING_FULL"));
    }

    @Test
    void invalidWebhookSignatureIsRejected() throws Exception {
        when(media.parseWebhook(anyString(), any())).thenThrow(new com.example.meetplatform.common.exception.ApiException(
                com.example.meetplatform.common.exception.ErrorCode.WEBHOOK_SIGNATURE_INVALID, "Invalid webhook signature."));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                        .header("Authorization", "forged"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
    }

    private void deliverWebhook(String id, MediaWebhookEvent.Type type, String room, String identity) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(
                new MediaWebhookEvent(id, type, type.name().toLowerCase(), room, identity, null, Instant.now()));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }

    private String state(String participantId) {
        return jdbc.queryForObject("select state from meeting_participants where id = ?", String.class,
                UUID.fromString(participantId));
    }

    private JsonNode json(String body) {
        return super.json.readTree(body);
    }
}
