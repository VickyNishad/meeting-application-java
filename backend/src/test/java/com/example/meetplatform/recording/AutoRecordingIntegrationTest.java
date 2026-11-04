package com.example.meetplatform.recording;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.storage.ObjectStorage;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.recording.entity.Recording;
import com.example.meetplatform.recording.repository.RecordingRepository;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auto-record: one composite room recording per session that follows the active speaker, started once the SFU
 * confirms the first participant and announced before capture. No per-participant files are created any more;
 * legacy ones stay visible to their subject and the host. Webinar recordings are marked stage-only.
 */
class AutoRecordingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    @Autowired
    RecordingRepository recordingRepository;

    private SignedIn host;
    private SignedIn attendee;
    private SignedIn outsider;
    private String roomEgress;

    @BeforeEach
    void setUp() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        roomEgress = "EG_room_" + UUID.randomUUID();
        when(media.startRoomRecording(anyString(), anyString(), anyString(), anyBoolean())).thenReturn(roomEgress);
        when(media.startParticipantRecording(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> "EG_p_" + inv.getArgument(1));
        when(storage.presignGet(anyString(), anyString(), any(), anyString()))
                .thenReturn(new ObjectStorage.PresignedUrl("https://storage.example.test/download", Instant.now().plusSeconds(900)));
        host = users.create(mvc, "Auto Host");
        attendee = users.create(mvc, "Auto Attendee");
        outsider = users.create(mvc, "Auto Outsider");
    }

    @Test
    void everySessionGetsOneSpeakerCompositeAnnouncedFirstAndNoPerPersonFiles() throws Exception {
        String meetingId = meeting(true);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        String attendeePid = join(meetingId, attendee).get("participantId").asString();
        String guestPid = guestJoin(meetingId, "Guest Gita").get("participantId").asString();

        joined(roomName, hostPid);
        joined(roomName, attendeePid);
        joined(roomName, guestPid);
        // Joining records nothing: the recording starts with the first speech, and only once.
        verify(media, never()).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());
        speaks(meetingId, hostJoin, host);
        speaks(meetingId, guestJoin(meetingId, "Guest Gopal"), null);

        verify(media, times(1)).startRoomRecording(eq(roomName), anyString(), eq("speaker"), eq(false));
        verify(media, never()).startParticipantRecording(anyString(), anyString(), anyString());
        InOrder order = inOrder(media);
        order.verify(media).sendData(eq(roomName), eq("recording"), any(), anyList());
        order.verify(media).startRoomRecording(eq(roomName), anyString(), eq("speaker"), eq(false));

        JsonNode all = list(meetingId, host);
        assertThat(all.size()).isEqualTo(1);
        assertThat(count(all, "CLOUD")).isEqualTo(1);
        JsonNode composite = all.get(0);
        assertThat(composite.get("layout").asString()).isEqualTo("speaker");
        assertThat(composite.get("contentType").asString()).isEqualTo("video/mp4");
        assertThat(composite.get("stageOnly").asBoolean()).isFalse();
        assertThat(composite.get("autoStarted").asBoolean()).isTrue();
    }

    /** The auto-started composite is the host's; attendees and outsiders cannot see or download it. */
    @Test
    void autoStartedRoomRecordingStaysWithTheHost() throws Exception {
        String meetingId = meeting(true);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String attendeePid = join(meetingId, attendee).get("participantId").asString();
        joined(roomName, hostJoin.get("participantId").asString());
        speaks(meetingId, hostJoin, host);
        joined(roomName, attendeePid);
        complete(roomEgress);
        String room = roomRecordingId(list(meetingId, host));

        assertThat(list(meetingId, attendee).size()).isZero();
        mvc.perform(get("/api/v1/recordings").header("Authorization", attendee.bearer()))
                .andExpect(jsonPath("$.items.length()").value(0));
        for (SignedIn other : new SignedIn[] {attendee, outsider}) {
            mvc.perform(get("/api/v1/recordings/" + room).header("Authorization", other.bearer()))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/recordings/" + room + "/download-url").header("Authorization", other.bearer()))
                    .andExpect(status().isNotFound());
            mvc.perform(delete("/api/v1/recordings/" + room).header("Authorization", other.bearer()))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/recordings").header("Authorization", host.bearer()))
                .andExpect(jsonPath("$.items[?(@.id == '" + room + "')]").exists());
        mvc.perform(post("/api/v1/recordings/" + room + "/download-url").header("Authorization", host.bearer()))
                .andExpect(status().isOk());
    }

    /** D4: per-person files recorded before the change stay readable by their subject and the host, nobody else. */
    @Test
    void legacyParticipantRecordingsStayVisibleToTheirSubject() throws Exception {
        String meetingId = meeting(true);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String attendeePid = join(meetingId, attendee).get("participantId").asString();
        joined(roomName, hostJoin.get("participantId").asString());
        speaks(meetingId, hostJoin, host);
        UUID roomId = recordingRepository.findAll().stream()
                .filter(r -> r.getMeetingId().toString().equals(meetingId)).findFirst().orElseThrow().getRoomId();
        Recording legacy = Recording.autoParticipant(UUID.fromString(meetingId), roomId, UUID.fromString(attendeePid),
                attendee.id(), "Auto Attendee", false, Instant.now());
        legacy.setStorageKey("recordings/" + meetingId + "/legacy.mp4");
        legacy.complete(1024L, 30L, Instant.now(), Instant.now().plusSeconds(86_400));
        String legacyId = recordingRepository.saveAndFlush(legacy).getId().toString();

        JsonNode mine = list(meetingId, attendee);
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).get("id").asString()).isEqualTo(legacyId);
        assertThat(mine.get(0).get("kind").asString()).isEqualTo("PARTICIPANT");
        mvc.perform(post("/api/v1/recordings/" + legacyId + "/download-url").header("Authorization", attendee.bearer()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/recordings/" + legacyId).header("Authorization", outsider.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/recordings/" + legacyId + "/download-url").header("Authorization", host.bearer()))
                .andExpect(status().isOk());
        // The subject still cannot delete it; the host can.
        mvc.perform(delete("/api/v1/recordings/" + legacyId).header("Authorization", attendee.bearer()))
                .andExpect(status().isForbidden());
    }

    /** D2: a webinar's composite shows the stage only and says so. */
    @Test
    void webinarRecordingIsMarkedStageOnly() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Webinar", "type", "INSTANT", "timeZone", "UTC",
                "autoRecord", true, "visibilityMode", "HOSTS_ONLY"));
        String meetingId = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.visibilityMode").value("HOST_AND_SELF_ONLY"))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        JsonNode hostJoin = join(meetingId, host);
        joined(hostJoin.get("roomName").asString(), hostJoin.get("participantId").asString());
        speaks(meetingId, hostJoin, host);

        JsonNode rec = list(meetingId, host).get(0);
        assertThat(rec.get("stageOnly").asBoolean()).isTrue();
        assertThat(rec.get("layout").asString()).isEqualTo("speaker");
    }

    @Test
    void reconnectsAndFurtherSpeechDoNotStartASecondRecording() throws Exception {
        String meetingId = meeting(true);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String pid = hostJoin.get("participantId").asString();
        joined(roomName, pid);
        speaks(meetingId, hostJoin, host);
        joined(roomName, pid);
        speaks(meetingId, hostJoin, host);
        reportsSpeech(meetingId, pid, pid, 5_000);
        verify(media, never()).startParticipantRecording(anyString(), anyString(), anyString());
        verify(media, times(1)).startRoomRecording(eq(roomName), anyString(), anyString(), anyBoolean());
    }

    @Test
    void nothingIsRecordedWhenAutoRecordIsOff() throws Exception {
        String meetingId = meeting(false);
        JsonNode hostJoin = join(meetingId, host);
        String pid = hostJoin.get("participantId").asString();
        joined(hostJoin.get("roomName").asString(), pid);
        reportsSpeech(meetingId, pid, pid, 5_000);
        verify(media, never()).startParticipantRecording(anyString(), anyString(), anyString());
        verify(media, never()).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());
    }

    /** A session in which nobody speaks leaves no recording at all, not even an empty or failed entry. */
    @Test
    void aSessionInWhichNobodySpeaksLeavesNoRecording() throws Exception {
        String meetingId = meeting(true);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        JsonNode attendeeJoin = join(meetingId, attendee);
        joined(roomName, hostJoin.get("participantId").asString());
        joined(roomName, attendeeJoin.get("participantId").asString());
        joined(roomName, guestJoin(meetingId, "Quiet Guest").get("participantId").asString());

        verify(media, never()).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());
        verify(media, never()).sendData(eq(roomName), eq("recording"), any(), anyList());
        assertThat(list(meetingId, host).size()).isZero();
        assertThat(recordingRepository.findAll().stream().filter(r -> r.getMeetingId().toString().equals(meetingId))).isEmpty();
    }

    /**
     * Browsers that cannot record speaking clips still start it through reported speaking time: a second or more of
     * speech does, a shorter blip does not.
     */
    @Test
    void reportedSpeakingTimeStartsTheRecordingButABlipDoesNot() throws Exception {
        String meetingId = meeting(true);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String hostPid = hostJoin.get("participantId").asString();
        String attendeePid = join(meetingId, attendee).get("participantId").asString();
        joined(roomName, hostPid);
        joined(roomName, attendeePid);

        reportsSpeech(meetingId, hostPid, attendeePid, 400);
        verify(media, never()).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());
        // Coughs or clicks briefly taken for speech: they add up past a second, but no single turn is that long.
        reportsSpeech(meetingId, hostPid, attendeePid, 2_800, 6, 500);
        verify(media, never()).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());
        reportsSpeech(meetingId, hostPid, attendeePid, 1_500);
        verify(media, times(1)).startRoomRecording(eq(roomName), anyString(), eq("speaker"), eq(false));
    }

    @Test
    void captureDoesNotStartWhenTheRoomCannotBeNotified() throws Exception {
        String meetingId = meeting(true);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        doThrow(new ApiException(ErrorCode.MEDIA_PROVIDER_UNAVAILABLE, "data channel down"))
                .when(media).sendData(eq(roomName), eq("recording"), any(), anyList());

        joined(roomName, hostJoin.get("participantId").asString());
        speaks(meetingId, hostJoin, host);

        verify(media, never()).startParticipantRecording(anyString(), anyString(), anyString());
        verify(media, never()).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());
        JsonNode all = list(meetingId, host);
        assertThat(all.size()).isEqualTo(1);
        all.forEach(r -> assertThat(r.get("status").asString()).isEqualTo("FAILED"));

        // More speech right after the failure does not leave a failed entry per turn.
        speaks(meetingId, hostJoin, host);
        speaks(meetingId, hostJoin, host);
        assertThat(list(meetingId, host).size()).isEqualTo(1);
    }

    @Test
    void autoRecordIsASettingTheJoinPageCanSee() throws Exception {
        String meetingId = meeting(false);
        JsonNode m = json.readTree(mvc.perform(patch("/api/v1/meetings/" + meetingId).header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("autoRecord", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.autoRecord").value(true))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/v1/meetings/by-code/" + m.get("joinCode").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.autoRecord").value(true));
    }

    /** AR-01: switching auto-record off stops its recordings, and nobody joining later is recorded. */
    @Test
    void switchingAutoRecordOffStopsItsRecordingsAndLaterJoinersAreNotRecorded() throws Exception {
        String meetingId = meeting(true);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        joined(roomName, hostJoin.get("participantId").asString());
        speaks(meetingId, hostJoin, host);

        mvc.perform(patch("/api/v1/meetings/" + meetingId).header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("autoRecord", false))))
                .andExpect(status().isOk());
        verify(media).stopRecording(roomEgress);
        list(meetingId, host).forEach(r -> assertThat(r.get("status").asString()).isEqualTo("STOPPING"));

        String attendeePid = join(meetingId, attendee).get("participantId").asString();
        joined(roomName, attendeePid);
        reportsSpeech(meetingId, hostJoin.get("participantId").asString(), attendeePid, 5_000);
        verify(media, never()).startParticipantRecording(anyString(), anyString(), anyString());
        verify(media, times(1)).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());
    }

    /** AR-01: whoever arrives while anything is recording is told so, on the join page and in the join response. */
    @Test
    void joinersAreToldWhenARecordingIsInProgress() throws Exception {
        String meetingId = meeting(false);
        JsonNode hostJoin = join(meetingId, host);
        String code = hostJoin.get("meeting").get("joinCode").asString();
        mvc.perform(get("/api/v1/meetings/by-code/" + code)).andExpect(jsonPath("$.recordingInProgress").value(false));

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", host.bearer()))
                .andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/meetings/by-code/" + code)).andExpect(jsonPath("$.recordingInProgress").value(true));
        assertThat(guestJoin(meetingId, "Late Guest").get("meeting").get("recordingInProgress").asBoolean()).isTrue();
    }

    /** AR-02: stopping the auto-started composite is announced once, as the (auto) room recording. */
    @Test
    void stoppingTheAutoRoomRecordingIsAnnouncedAsTheRoomRecording() throws Exception {
        String meetingId = meeting(true);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String attendeePid = join(meetingId, attendee).get("participantId").asString();
        joined(roomName, hostJoin.get("participantId").asString());
        speaks(meetingId, hostJoin, host);
        joined(roomName, attendeePid);

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings/" + roomRecordingId(list(meetingId, host)) + "/stop")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isAccepted());

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(media, atLeastOnce()).sendData(eq(roomName), eq("recording"), payloads.capture(), anyList());
        List<JsonNode> stopped = payloads.getAllValues().stream()
                .map(b -> json.readTree(new String(b, StandardCharsets.UTF_8)))
                .filter(p -> "STOPPED".equals(p.get("state").asString())).toList();
        assertThat(stopped).hasSize(1);
        assertThat(stopped.get(0).get("kind").asString()).isEqualTo("CLOUD");
        assertThat(stopped.get(0).get("auto").asBoolean()).isTrue();
        assertThat(stopped.get(0).hasNonNull("participantId")).isFalse();
    }

    /** AR-03: a moderator's stop holds across joins, reconnects and further speech, until auto-record is switched off and on. */
    @Test
    void aModeratorsStopIsNotUndoneByTheNextJoin() throws Exception {
        String meetingId = meeting(true);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String attendeePid = join(meetingId, attendee).get("participantId").asString();
        joined(roomName, hostJoin.get("participantId").asString());
        speaks(meetingId, hostJoin, host);
        joined(roomName, attendeePid);
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings/" + roomRecordingId(list(meetingId, host)) + "/stop")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isAccepted());
        complete(roomEgress);

        joined(roomName, attendeePid); // reconnect
        JsonNode newcomer = guestJoin(meetingId, "Newcomer");
        joined(roomName, newcomer.get("participantId").asString());
        speaks(meetingId, newcomer, null);
        speaks(meetingId, hostJoin, host);
        verify(media, times(1)).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());

        for (boolean on : new boolean[] {false, true}) {
            mvc.perform(patch("/api/v1/meetings/" + meetingId).header("Authorization", host.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("autoRecord", on))))
                    .andExpect(status().isOk());
        }
        joined(roomName, attendeePid);
        verify(media, times(1)).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());
        speaks(meetingId, hostJoin, host);
        verify(media, times(2)).startRoomRecording(anyString(), anyString(), anyString(), anyBoolean());
        verify(media, never()).startParticipantRecording(anyString(), anyString(), anyString());
    }

    /** AR-04: a co-host sees the live session's recordings only while present in it. */
    @Test
    void coHostSeesTheLiveSessionsRecordingsOnlyWhilePresent() throws Exception {
        String meetingId = meeting(true);
        JsonNode hostJoin = join(meetingId, host);
        String roomName = hostJoin.get("roomName").asString();
        String coPid = join(meetingId, attendee).get("participantId").asString();
        String guestPid = guestJoin(meetingId, "Guest Ganesh").get("participantId").asString();
        joined(roomName, hostJoin.get("participantId").asString());
        speaks(meetingId, hostJoin, host);
        joined(roomName, coPid);
        joined(roomName, guestPid);
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/moderation/participants/" + coPid + "/role")
                        .header("Authorization", host.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("role", "CO_HOST"))))
                .andExpect(status().isNoContent());

        // The live session's composite (auto-started, so otherwise the host's).
        assertThat(list(meetingId, attendee).size()).isEqualTo(1);

        mvc.perform(post("/api/v1/meetings/" + meetingId + "/leave").header("Authorization", attendee.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("participantId", coPid))))
                .andExpect(status().isNoContent());
        assertThat(list(meetingId, attendee).size()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String meeting(boolean autoRecord) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Auto " + autoRecord, "type", "INSTANT",
                "timeZone", "UTC", "autoRecord", autoRecord));
        return json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.autoRecord").value(autoRecord))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
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

    private JsonNode list(String meetingId, SignedIn user) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/meetings/" + meetingId + "/recordings")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /**
     * The participant starts speaking: its browser opens a speaking clip (it does so only once it hears a voice).
     * {@code user} null: a guest, authenticated with its participant secret.
     */
    private void speaks(String meetingId, JsonNode join, SignedIn user) throws Exception {
        var request = post("/api/v1/meetings/" + meetingId + "/speech-recordings").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("participantId", join.get("participantId").asString(), "format", "audio/webm;codecs=opus")));
        request = user != null ? request.header("Authorization", user.bearer()) : request.header("X-Participant-Token", join.get("waitToken").asString());
        mvc.perform(request).andExpect(status().isCreated());
    }

    /** The reporting moderator's browser reports {@code speakingMs} of speech by {@code speakerPid}. */
    private void reportsSpeech(String meetingId, String reporterPid, String speakerPid, long speakingMs) throws Exception {
        reportsSpeech(meetingId, reporterPid, speakerPid, speakingMs, 1, speakingMs);
    }

    private void reportsSpeech(String meetingId, String reporterPid, String speakerPid, long speakingMs, int turns,
                               long longestTurnMs) throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/speaker-stats").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", reporterPid, "batchSeq", ++reportSeq,
                                "entries", List.of(Map.of("participantId", speakerPid, "speakingMs", speakingMs, "turns", turns,
                                        "longestTurnMs", longestTurnMs))))))
                .andExpect(status().is2xxSuccessful());
    }

    private long reportSeq;

    private void joined(String roomName, String participantId) throws Exception {
        webhook(new MediaWebhookEvent("EV_" + UUID.randomUUID(), MediaWebhookEvent.Type.PARTICIPANT_JOINED,
                "participant_joined", roomName, participantId, null, Instant.now()));
    }

    private void complete(String egressId) throws Exception {
        webhook(new MediaWebhookEvent("EV_" + UUID.randomUUID(), MediaWebhookEvent.Type.EGRESS_ENDED, "egress_ended",
                null, null, new MediaWebhookEvent.EgressUpdate(egressId, MediaWebhookEvent.EgressStatus.COMPLETE, null,
                        "p.mp4", 2048L, 60L, Instant.now().minusSeconds(60), Instant.now()), Instant.now()));
    }

    private void webhook(MediaWebhookEvent event) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(event);
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }

    private static long count(JsonNode list, String kind) {
        long n = 0;
        for (JsonNode r : list) {
            if (kind.equals(r.get("kind").asString())) {
                n++;
            }
        }
        return n;
    }

    private static String roomRecordingId(JsonNode list) {
        for (JsonNode r : list) {
            if ("CLOUD".equals(r.get("kind").asString())) {
                return r.get("id").asString();
            }
        }
        throw new AssertionError("no room recording");
    }
}
