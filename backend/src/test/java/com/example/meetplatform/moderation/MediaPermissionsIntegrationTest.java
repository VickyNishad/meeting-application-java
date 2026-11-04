package com.example.meetplatform.moderation;

import com.example.meetplatform.media.dto.MediaPermissions;
import com.example.meetplatform.media.dto.MediaTokenRequest;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.media.dto.TrackSource;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.example.meetplatform.media.dto.TrackSource.CAMERA;
import static com.example.meetplatform.media.dto.TrackSource.MICROPHONE;
import static com.example.meetplatform.media.dto.TrackSource.SCREEN_SHARE;
import static com.example.meetplatform.media.dto.TrackSource.SCREEN_SHARE_AUDIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Host-controlled microphone / camera / screen-sharing permissions: meeting-wide setting, per-participant overrides,
 * precedence, host-only changes, and enforcement through the media server's publish grants (token and live update).
 */
class MediaPermissionsIntegrationTest extends AbstractIntegrationTest {

    private static final Set<TrackSource> ALL = EnumSet.of(MICROPHONE, CAMERA, SCREEN_SHARE, SCREEN_SHARE_AUDIO);
    private static final String TOKEN = "X-Participant-Token";

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    private SignedIn host;
    private SignedIn rahulUser;
    private String meetingId;
    private String roomName;
    private String hostPid;
    private Who rahul;
    private Who amit;
    private Who neha;

    record Who(String pid, String waitToken, SignedIn user) {
        MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r) {
            return user != null ? r.header("Authorization", user.bearer()) : r.header(TOKEN, waitToken);
        }
    }

    @BeforeEach
    void liveMeetingWithThreeParticipants() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        host = users.create(mvc, "Perm Host");
        meetingId = createMeeting(host, "VIDEO");
        JsonNode hostJoin = joinJson(meetingId, host, null);
        roomName = hostJoin.get("roomName").asString();
        hostPid = hostJoin.get("participantId").asString();
        connect(hostPid);
        rahulUser = users.create(mvc, "Rahul Sharma");
        rahul = join(meetingId, rahulUser, null);
        amit = join(meetingId, null, "Amit Kumar");
        neha = join(meetingId, null, "Neha");
    }

    // ------------------------------------------------------------------ meeting-wide

    @Test
    void restrictingTheMicrophoneForEveryoneRevokesItOnTheMediaServerButNotForTheHost() throws Exception {
        clearInvocations(media);
        global(host, Map.of("microphone", "RESTRICTED")).andExpect(status().isOk());

        for (Who p : List.of(rahul, amit, neha)) {
            assertThat(liveGrant(p.pid())).doesNotContain(MICROPHONE).contains(CAMERA, SCREEN_SHARE);
        }
        assertThat(liveGrant(hostPid)).containsAll(ALL);
        assertThat(signalTypes(List.of())).contains("media-permissions");
        assertThat(state(amit).get("mediaPermissions").get("microphone").asString()).isEqualTo("RESTRICTED");
        assertThat(state(amit).get("myMedia").get("microphone").asBoolean()).isFalse();

        global(host, Map.of("microphone", "ALLOWED")).andExpect(status().isOk());
        for (Who p : List.of(rahul, amit, neha)) {
            assertThat(liveGrant(p.pid())).containsAll(ALL);
        }
    }

    @Test
    void microphoneCameraAndScreenSharingAreIndependent() throws Exception {
        global(host, Map.of("camera", "RESTRICTED")).andExpect(status().isOk());
        assertThat(liveGrant(amit.pid())).contains(MICROPHONE, SCREEN_SHARE).doesNotContain(CAMERA);
        global(host, Map.of("screenShare", "RESTRICTED")).andExpect(status().isOk());
        assertThat(liveGrant(amit.pid())).contains(MICROPHONE).doesNotContain(CAMERA, SCREEN_SHARE, SCREEN_SHARE_AUDIO);
        global(host, Map.of("camera", "ALLOWED")).andExpect(status().isOk());
        assertThat(liveGrant(amit.pid())).contains(MICROPHONE, CAMERA).doesNotContain(SCREEN_SHARE);
        // The screen-sharing permission is the meeting's screen-sharing policy.
        assertThat(meeting().get("screenSharePolicy").asString()).isEqualTo("HOSTS_ONLY");
    }

    // ------------------------------------------------------------------ overrides and precedence

    @Test
    void anIndividualOverrideBeatsTheMeetingSettingAndDefaultReturnsToIt() throws Exception {
        global(host, Map.of("microphone", "RESTRICTED")).andExpect(status().isOk());
        participant(host, rahul.pid(), Map.of("microphone", "ALLOWED")).andExpect(status().isOk());
        participant(host, amit.pid(), Map.of("microphone", "RESTRICTED")).andExpect(status().isOk());
        assertThat(liveGrant(rahul.pid())).contains(MICROPHONE);
        assertThat(liveGrant(amit.pid())).doesNotContain(MICROPHONE);
        assertThat(liveGrant(neha.pid())).doesNotContain(MICROPHONE);

        global(host, Map.of("microphone", "ALLOWED")).andExpect(status().isOk());
        assertThat(liveGrant(rahul.pid())).contains(MICROPHONE);
        assertThat(liveGrant(amit.pid())).doesNotContain(MICROPHONE); // still restricted individually
        assertThat(liveGrant(neha.pid())).contains(MICROPHONE);

        global(host, Map.of("microphone", "RESTRICTED")).andExpect(status().isOk());
        JsonNode back = json.readTree(participant(host, rahul.pid(), Map.of("microphone", "DEFAULT"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(back.get("microphoneOverride").isNull()).isTrue();
        assertThat(back.get("microphone").asBoolean()).isFalse();
        assertThat(liveGrant(rahul.pid())).doesNotContain(MICROPHONE);
        // Only that medium changed.
        assertThat(liveGrant(rahul.pid())).contains(CAMERA, SCREEN_SHARE);
    }

    @Test
    void overridesWorkPerMediumForCameraAndScreenSharingToo() throws Exception {
        global(host, Map.of("camera", "RESTRICTED", "screenShare", "RESTRICTED")).andExpect(status().isOk());
        participant(host, rahul.pid(), Map.of("camera", "ALLOWED", "screenShare", "ALLOWED")).andExpect(status().isOk());
        assertThat(liveGrant(rahul.pid())).containsAll(ALL);
        assertThat(liveGrant(amit.pid())).contains(MICROPHONE).doesNotContain(CAMERA, SCREEN_SHARE);
        participant(host, rahul.pid(), Map.of("screenShare", "DEFAULT")).andExpect(status().isOk());
        assertThat(liveGrant(rahul.pid())).contains(CAMERA).doesNotContain(SCREEN_SHARE);
    }

    // ------------------------------------------------------------------ who may change them

    @Test
    void onlyTheHostCanChangeMediaPermissions() throws Exception {
        // A participant (signed in), a co-host, a guest and an anonymous caller are all refused.
        global(rahulUser, Map.of("microphone", "ALLOWED")).andExpect(status().isForbidden());
        participant(rahulUser, rahul.pid(), Map.of("microphone", "ALLOWED")).andExpect(status().isForbidden());
        SignedIn coUser = users.create(mvc, "Co Host");
        Who co = join(meetingId, coUser, null);
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + co.pid() + "/role")
                .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("role", "CO_HOST")))).andExpect(status().isNoContent());
        global(coUser, Map.of("microphone", "RESTRICTED")).andExpect(status().isForbidden());
        participant(coUser, amit.pid(), Map.of("microphone", "RESTRICTED")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/meetings/" + meetingId + "/moderation/settings").header("Authorization", coUser.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("screenSharePolicy", "HOSTS_ONLY"))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/meetings/" + meetingId + "/moderation/media-permissions").header(TOKEN, amit.waitToken())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("microphone", "ALLOWED"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/meetings/" + meetingId + "/moderation/media-permissions")
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("microphone", "ALLOWED"))))
                .andExpect(status().isUnauthorized());

        // Nothing changed.
        assertThat(state(amit).get("mediaPermissions").get("microphone").asString()).isEqualTo("ALLOWED");
        assertThat(state(co).has("participantMedia")).isFalse(); // co-hosts do not get everyone's rights
        assertThat(state(amit).has("participantMedia")).isFalse();
        // Co-hosts keep full rights whatever the meeting says, and take no overrides.
        global(host, Map.of("microphone", "RESTRICTED", "camera", "RESTRICTED", "screenShare", "RESTRICTED"))
                .andExpect(status().isOk());
        assertThat(liveGrant(co.pid())).containsAll(ALL);
        participant(host, co.pid(), Map.of("microphone", "RESTRICTED")).andExpect(status().isConflict());
        participant(host, hostPid, Map.of("microphone", "RESTRICTED")).andExpect(status().isConflict());
    }

    @Test
    void participantIdsAreVerifiedAgainstTheMeetingAndRequestsAreValidated() throws Exception {
        // A participant of another meeting (even one this host hosts) and unknown ids are not found here.
        String other = createMeeting(host, "VIDEO");
        Who stranger = join(other, null, "Stranger");
        participant(host, stranger.pid(), Map.of("microphone", "RESTRICTED")).andExpect(status().isNotFound());
        participant(host, UUID.randomUUID().toString(), Map.of("microphone", "RESTRICTED")).andExpect(status().isNotFound());
        // Another host cannot touch this meeting (and is not told it exists).
        SignedIn otherHost = users.create(mvc, "Other Host");
        global(otherHost, Map.of("microphone", "RESTRICTED")).andExpect(status().isNotFound());
        participant(otherHost, amit.pid(), Map.of("microphone", "RESTRICTED")).andExpect(status().isNotFound());
        // Empty and invalid bodies.
        global(host, Map.of()).andExpect(status().isBadRequest());
        participant(host, amit.pid(), Map.of()).andExpect(status().isBadRequest());
        global(host, Map.of("microphone", "MAYBE")).andExpect(status().isBadRequest());
        participant(host, amit.pid(), Map.of("camera", "YES")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select mic_permission from meetings where id = ?", String.class,
                UUID.fromString(meetingId))).isEqualTo("ALLOWED");
    }

    // ------------------------------------------------------------------ late joiners, reconnects, sync

    @Test
    void lateJoinersAndReconnectsGetTheCurrentRightsInTheirToken() throws Exception {
        global(host, Map.of("microphone", "RESTRICTED", "camera", "RESTRICTED")).andExpect(status().isOk());
        participant(host, rahul.pid(), Map.of("microphone", "ALLOWED")).andExpect(status().isOk());
        clearInvocations(media);

        Who late = join(meetingId, null, "Late Lisa");
        assertThat(tokenGrant(late.pid())).contains(SCREEN_SHARE).doesNotContain(MICROPHONE, CAMERA);
        // A reload within the reconnect window keeps the participation and its override.
        Who again = join(meetingId, rahulUser, null);
        assertThat(again.pid()).isEqualTo(rahul.pid());
        assertThat(tokenGrant(rahul.pid())).contains(MICROPHONE).doesNotContain(CAMERA);
        JsonNode mine = state(again).get("myMedia");
        assertThat(mine.get("microphoneOverride").asString()).isEqualTo("ALLOWED");
        assertThat(mine.get("microphone").asBoolean()).isTrue();
    }

    @Test
    void aSignedInParticipantKeepsOverridesWhenRejoiningAfterTheReconnectWindow() throws Exception {
        participant(host, rahul.pid(), Map.of("microphone", "RESTRICTED", "camera", "RESTRICTED")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/leave").header("Authorization", rahulUser.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("participantId", rahul.pid()))))
                .andExpect(status().isNoContent());
        jdbc.update("update meeting_participants set left_at = now() - interval '1 hour' where id = ?",
                UUID.fromString(rahul.pid()));
        clearInvocations(media);

        Who back = join(meetingId, rahulUser, null);
        assertThat(back.pid()).isNotEqualTo(rahul.pid()); // a new participation
        assertThat(tokenGrant(back.pid())).doesNotContain(MICROPHONE, CAMERA).contains(SCREEN_SHARE);
        // A guest is not recognised: a new guest starts from the meeting's permissions.
        participant(host, amit.pid(), Map.of("microphone", "RESTRICTED")).andExpect(status().isOk());
        Who newGuest = join(meetingId, null, "Amit Kumar");
        assertThat(tokenGrant(newGuest.pid())).contains(MICROPHONE);
    }

    @Test
    void theHostIsToldEveryChangeAndAParticipantOnlyItsOwn() throws Exception {
        clearInvocations(media);
        participant(host, amit.pid(), Map.of("microphone", "RESTRICTED")).andExpect(status().isOk());
        List<JsonNode> toHost = signals(List.of(hostPid));
        List<JsonNode> toAmit = signals(List.of(amit.pid()));
        assertThat(toHost).anySatisfy(s -> {
            assertThat(s.get("type").asString()).isEqualTo("participant-media");
            assertThat(s.get("participants").get(0).get("participantId").asString()).isEqualTo(amit.pid());
            assertThat(s.get("participants").get(0).get("microphone").asBoolean()).isFalse();
        });
        assertThat(toAmit).anySatisfy(s -> assertThat(s.get("type").asString()).isEqualTo("participant-media"));
        // Nobody else (and no broadcast) learns about Amit's override.
        assertThat(signals(List.of(neha.pid()))).isEmpty();
        assertThat(signalTypes(List.of())).doesNotContain("participant-media");
        // The host's snapshot lists everyone's rights.
        JsonNode hostState = json.readTree(mvc.perform(get("/api/v1/meetings/" + meetingId + "/room-state")
                .param("participantId", hostPid).header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> listed = new ArrayList<>();
        hostState.get("participantMedia").forEach(m -> listed.add(m.get("participantId").asString()
                + ":" + m.get("microphone").asBoolean()));
        assertThat(listed).contains(amit.pid() + ":false", neha.pid() + ":true", rahul.pid() + ":true");
    }

    @Test
    void concurrentChangesAreAllApplied() throws Exception {
        CompletableFuture<Integer> mic = CompletableFuture.supplyAsync(() -> httpStatus(() -> global(host, Map.of("microphone", "RESTRICTED"))));
        CompletableFuture<Integer> cam = CompletableFuture.supplyAsync(() -> httpStatus(() -> global(host, Map.of("camera", "RESTRICTED"))));
        CompletableFuture<Integer> one = CompletableFuture.supplyAsync(() -> httpStatus(() -> participant(host, rahul.pid(), Map.of("screenShare", "RESTRICTED"))));
        assertThat(List.of(mic.get(), cam.get(), one.get())).containsOnly(200);
        JsonNode p = state(neha).get("mediaPermissions");
        assertThat(p.get("microphone").asString()).isEqualTo("RESTRICTED");
        assertThat(p.get("camera").asString()).isEqualTo("RESTRICTED");
        assertThat(state(rahul).get("myMedia").get("screenShareOverride").asString()).isEqualTo("RESTRICTED");
    }

    @Test
    void inAHostAndSelfOnlyMeetingTheAttendeesOwnRoomIsRestricted() throws Exception {
        String webinar = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("title", "Webinar", "type", "INSTANT",
                                "timeZone", "UTC", "visibilityMode", "HOST_AND_SELF_ONLY"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        JsonNode hostJoin = joinJson(webinar, host, null);
        String stage = hostJoin.get("roomName").asString();
        connect(hostJoin.get("participantId").asString(), stage);
        Who attendee = join(webinar, null, "Attendee A");
        clearInvocations(media);
        mvc.perform(put("/api/v1/meetings/" + webinar + "/moderation/media-permissions").header("Authorization", host.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("microphone", "RESTRICTED"))))
                .andExpect(status().isOk());

        ArgumentCaptor<String> rooms = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MediaPermissions> grants = ArgumentCaptor.forClass(MediaPermissions.class);
        verify(media, atLeastOnce()).updateParticipant(rooms.capture(), eq(attendee.pid()), any(), grants.capture());
        // The attendee publishes only in its own audience room: that is where the microphone is withdrawn.
        int i = rooms.getAllValues().indexOf(rooms.getAllValues().stream().filter(r -> !r.equals(stage)).findFirst().orElseThrow());
        assertThat(grants.getAllValues().get(i).publishSources()).doesNotContain(MICROPHONE).contains(CAMERA);
        assertThat(grants.getAllValues().get(rooms.getAllValues().indexOf(stage)).publishSources()).isEmpty();
    }

    /**
     * The host drops out: the existing rule promotes the earliest signed-in participant to co-host after a grace. The
     * new co-host gets full media rights; everyone else stays restricted; the co-host cannot change the permissions.
     */
    @Test
    void whenTheHostLeavesThePromotedCoHostMayPublishAndTheRestStayRestricted() throws Exception {
        global(host, Map.of("microphone", "RESTRICTED")).andExpect(status().isOk());
        clearInvocations(media);
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_perm_left_" + UUID.randomUUID(),
                MediaWebhookEvent.Type.PARTICIPANT_LEFT, "participant_left", roomName, hostPid, null, Instant.now()));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());

        long until = System.currentTimeMillis() + 20_000;
        while (!"CO_HOST".equals(state(rahul).get("role").asString()) && System.currentTimeMillis() < until) {
            Thread.sleep(250);
        }
        assertThat(state(rahul).get("role").asString()).isEqualTo("CO_HOST");
        assertThat(liveGrant(rahul.pid())).containsAll(ALL);
        assertThat(state(amit).get("myMedia").get("microphone").asBoolean()).isFalse();
        global(rahulUser, Map.of("microphone", "ALLOWED")).andExpect(status().isForbidden());
        // The setting stays with the meeting for when the host is back.
        assertThat(state(neha).get("mediaPermissions").get("microphone").asString()).isEqualTo("RESTRICTED");
    }

    @Test
    void audioOnlyMeetingsNeverGrantCameraOrScreenSharing() throws Exception {
        String audio = createMeeting(host, "AUDIO_ONLY");
        joinJson(audio, host, null);
        clearInvocations(media);
        Who p = join(audio, null, "Listener");
        assertThat(tokenGrant(p.pid())).containsExactly(MICROPHONE);
        verify(media, never()).muteTrack(anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------ QA findings (F1-F7)

    /** F3: screen-share audio could carry a microphone, so it needs the microphone permission too. */
    @Test
    void screenShareAudioNeedsTheMicrophonePermissionToo() throws Exception {
        global(host, Map.of("microphone", "RESTRICTED")).andExpect(status().isOk());
        assertThat(liveGrant(amit.pid())).contains(SCREEN_SHARE).doesNotContain(MICROPHONE, SCREEN_SHARE_AUDIO);
        participant(host, rahul.pid(), Map.of("microphone", "ALLOWED")).andExpect(status().isOk());
        assertThat(liveGrant(rahul.pid())).containsAll(ALL);
        participant(host, neha.pid(), Map.of("screenShare", "RESTRICTED", "microphone", "ALLOWED")).andExpect(status().isOk());
        assertThat(liveGrant(neha.pid())).contains(MICROPHONE).doesNotContain(SCREEN_SHARE, SCREEN_SHARE_AUDIO);
        Who late = join(meetingId, null, "Late");
        assertThat(tokenGrant(late.pid())).contains(SCREEN_SHARE).doesNotContain(SCREEN_SHARE_AUDIO);
    }

    /** F2: a guest who joins again with the participant secret of its earlier participation keeps its restrictions. */
    @Test
    void aGuestProvingItsEarlierParticipationKeepsItsRestrictions() throws Exception {
        participant(host, amit.pid(), Map.of("microphone", "RESTRICTED", "camera", "RESTRICTED")).andExpect(status().isOk());
        // Within the reconnect window: the same participation.
        Who again = rejoin(amit, amit.waitToken());
        assertThat(again.pid()).isEqualTo(amit.pid());
        assertThat(tokenGrant(amit.pid())).doesNotContain(MICROPHONE, CAMERA);

        // Left, and back long after the window: a new participation that keeps the overrides.
        mvc.perform(again.auth(post("/api/v1/meetings/" + meetingId + "/leave")).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("participantId", again.pid())))).andExpect(status().isNoContent());
        jdbc.update("update meeting_participants set left_at = now() - interval '1 hour' where id = ?",
                UUID.fromString(again.pid()));
        Who later = rejoin(again, again.waitToken());
        assertThat(later.pid()).isNotEqualTo(amit.pid());
        assertThat(tokenGrant(later.pid())).doesNotContain(MICROPHONE, CAMERA).contains(SCREEN_SHARE);
        assertThat(state(later).get("myMedia").get("microphoneOverride").asString()).isEqualTo("RESTRICTED");

        // A wrong secret proves nothing: a new guest with the meeting's permissions.
        Who stranger = rejoin(later, "not-the-secret");
        assertThat(stranger.pid()).isNotEqualTo(later.pid());
        assertThat(tokenGrant(stranger.pid())).containsAll(ALL);
    }

    /** F4: screen sharing changed through the meeting form while live is enforced and announced like the host's switch. */
    @Test
    void changingScreenSharingInTheMeetingFormWhileLiveIsEnforced() throws Exception {
        clearInvocations(media);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/meetings/" + meetingId)
                .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("screenSharePolicy", "HOSTS_ONLY")))).andExpect(status().isOk());
        assertThat(liveGrant(amit.pid())).contains(MICROPHONE, CAMERA).doesNotContain(SCREEN_SHARE, SCREEN_SHARE_AUDIO);
        assertThat(liveGrant(hostPid)).containsAll(ALL);
        assertThat(signals(List.of())).anySatisfy(s -> {
            assertThat(s.get("type").asString()).isEqualTo("media-permissions");
            assertThat(s.get("screenShare").asString()).isEqualTo("RESTRICTED");
        });
        // Switching a live meeting to audio only withdraws camera and screen sharing on the SFU too.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/meetings/" + meetingId)
                .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mediaMode", "AUDIO_ONLY")))).andExpect(status().isOk());
        assertThat(liveGrant(amit.pid())).containsExactly(MICROPHONE);
    }

    /** F6: the host's view of someone's rights follows a promotion and a demotion. */
    @Test
    void theHostIsToldTheRightsOfSomeonePromotedOrDemoted() throws Exception {
        global(host, Map.of("microphone", "RESTRICTED")).andExpect(status().isOk());
        for (String role : List.of("CO_HOST", "PARTICIPANT")) {
            clearInvocations(media);
            mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + rahul.pid() + "/role")
                    .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("role", role)))).andExpect(status().isNoContent());
            boolean coHost = role.equals("CO_HOST");
            assertThat(signals(List.of(hostPid))).anySatisfy(s -> {
                assertThat(s.get("type").asString()).isEqualTo("participant-media");
                assertThat(s.get("participants").get(0).get("participantId").asString()).isEqualTo(rahul.pid());
                assertThat(s.get("participants").get(0).get("microphone").asBoolean()).isEqualTo(coHost);
            });
            assertThat(liveGrant(rahul.pid()).contains(MICROPHONE)).isEqualTo(coHost);
        }
    }

    /** F7: enum values only by name. */
    @Test
    void numbersAreNotAcceptedAsPermissionValues() throws Exception {
        global(host, Map.of("microphone", 1)).andExpect(status().isBadRequest());
        participant(host, amit.pid(), Map.of("microphone", 2)).andExpect(status().isBadRequest());
        assertThat(state(amit).get("mediaPermissions").get("microphone").asString()).isEqualTo("ALLOWED");
        JsonNode override = state(amit).get("myMedia").path("microphoneOverride");
        assertThat(override.isNull() || override.isMissingNode()).isTrue();
    }

    /**
     * F1: the host restricts someone admitted whose connection is not confirmed yet. The change reaches it at once
     * (pushed to admitted participants too), the confirmation does not undo the override, and the connection ends up
     * with the current rights.
     */
    @Test
    void aRestrictionMadeAsTheConnectionIsConfirmedIsKept() throws Exception {
        JsonNode j = joinJson(meetingId, null, "Connecting");
        String pid = j.get("participantId").asString();
        clearInvocations(media);
        participant(host, pid, Map.of("microphone", "RESTRICTED")).andExpect(status().isOk());
        assertThat(liveGrant(pid)).doesNotContain(MICROPHONE);
        global(host, Map.of("camera", "RESTRICTED")).andExpect(status().isOk());
        connect(pid);
        assertThat(jdbc.queryForObject("select state from meeting_participants where id = ?", String.class,
                UUID.fromString(pid))).isEqualTo("JOINED");
        assertThat(jdbc.queryForObject("select mic_override from meeting_participants where id = ?", String.class,
                UUID.fromString(pid))).isEqualTo("RESTRICTED");
        assertThat(liveGrant(pid)).doesNotContain(MICROPHONE, CAMERA).contains(SCREEN_SHARE);
    }

    // ------------------------------------------------------------------ helpers

    /** A guest joins again from the same browser, presenting its earlier participation and secret. */
    private Who rejoin(Who earlier, String secret) throws Exception {
        JsonNode j = json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("displayName", "Amit Kumar",
                                "rejoin", Map.of("participantId", earlier.pid(), "token", secret)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String pid = j.get("participantId").asString();
        connect(pid);
        return new Who(pid, j.get("waitToken").asString(), null);
    }

    private String createMeeting(SignedIn owner, String mediaMode) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Perms", "type", "INSTANT", "timeZone", "UTC", "mediaMode", mediaMode))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private JsonNode joinJson(String meeting, SignedIn user, String guestName) throws Exception {
        MockHttpServletRequestBuilder r = post("/api/v1/meetings/" + meeting + "/join");
        if (user != null) {
            r = r.header("Authorization", user.bearer());
        } else {
            r = r.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("displayName", guestName)));
        }
        return json.readTree(mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Who join(String meeting, SignedIn user, String guestName) throws Exception {
        JsonNode j = joinJson(meeting, user, guestName);
        String pid = j.get("participantId").asString();
        if (j.hasNonNull("roomName")) {
            connect(pid, j.get("roomName").asString());
        }
        return new Who(pid, j.get("waitToken").asString(), user);
    }

    private void connect(String pid) throws Exception {
        connect(pid, roomName);
    }

    /** The media server reports the participant connected (it is JOINED: live grant updates now reach it). */
    private void connect(String pid, String room) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_perm_" + UUID.randomUUID(),
                MediaWebhookEvent.Type.PARTICIPANT_JOINED, "participant_joined", room, pid, null, Instant.now()));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }

    private ResultActions global(SignedIn who, Map<String, ?> body) throws Exception {
        return mvc.perform(put("/api/v1/meetings/" + meetingId + "/moderation/media-permissions")
                .header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private ResultActions participant(SignedIn who, String pid, Map<String, ?> body) throws Exception {
        return mvc.perform(put("/api/v1/meetings/" + meetingId + "/moderation/participants/" + pid + "/media-permissions")
                .header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private interface Call {
        ResultActions run() throws Exception;
    }

    private static int httpStatus(Call call) {
        try {
            return call.run().andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode state(Who who) throws Exception {
        return json.readTree(mvc.perform(who.auth(get("/api/v1/meetings/" + meetingId + "/room-state")
                        .param("participantId", who.pid())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode meeting() throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/meetings/" + meetingId).header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** The latest publish grant pushed live to this participant on the media server. */
    private Set<TrackSource> liveGrant(String pid) {
        ArgumentCaptor<MediaPermissions> captor = ArgumentCaptor.forClass(MediaPermissions.class);
        verify(media, atLeastOnce()).updateParticipant(eq(roomName), eq(pid), any(), captor.capture());
        return captor.getValue().publishSources();
    }

    /** The publish grant in the latest token issued to this participant. */
    private Set<TrackSource> tokenGrant(String pid) {
        ArgumentCaptor<MediaTokenRequest> captor = ArgumentCaptor.forClass(MediaTokenRequest.class);
        verify(media, atLeastOnce()).issueToken(captor.capture());
        List<MediaTokenRequest> mine = captor.getAllValues().stream().filter(t -> t.identity().equals(pid)).toList();
        assertThat(mine).isNotEmpty();
        return mine.getLast().permissions().publishSources();
    }

    /** Data messages sent to exactly these identities (an empty list = everyone). */
    private List<JsonNode> signals(List<String> to) {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> identities = ArgumentCaptor.forClass(List.class);
        try {
            verify(media, atLeastOnce()).sendData(eq(roomName), eq("moderation"), payload.capture(), identities.capture());
        } catch (AssertionError none) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        for (int i = 0; i < payload.getAllValues().size(); i++) {
            if (identities.getAllValues().get(i).equals(to)) {
                out.add(json.readTree(new String(payload.getAllValues().get(i), StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    private List<String> signalTypes(List<String> to) {
        return signals(to).stream().map(s -> s.get("type").asString()).toList();
    }

}
