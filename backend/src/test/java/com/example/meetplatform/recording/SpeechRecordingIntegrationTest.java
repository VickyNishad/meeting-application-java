package com.example.meetplatform.recording;

import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.recording.service.SpeechRecordingService;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Speaking clips end to end over HTTP: folder layout and names on disk, chunk rules, completion only when verified,
 * recovery of unfinished clips, and who may see and play what.
 */
class SpeechRecordingIntegrationTest extends AbstractIntegrationTest {

    private static final Path ROOT;
    private static final byte[] WEBM_HEAD = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0x42, (byte) 0x86, (byte) 0x81, 0x01};
    private static final String TOKEN = "X-Participant-Token";

    static {
        try {
            ROOT = Files.createTempDirectory("speech-clips-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.speech-recordings.storage-path", ROOT::toString);
    }

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SpeechRecordingService service;

    private SignedIn host;

    @BeforeEach
    void setUp() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        host = users.create(mvc, "Clip Host");
    }

    /** A participant in a live meeting: its id, its secret (guests) or user (signed in). */
    record Who(String meetingId, String pid, String secret, SignedIn user) {
        MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r) {
            return user != null ? r.header("Authorization", user.bearer()) : r.header(TOKEN, secret);
        }
    }

    @Test
    void clipsAreFiledByDateMeetingAndParticipantAndMatchTheDatabase() throws Exception {
        String meetingA = meeting("Q3 review: <draft> & \"final\"?", true);
        Who hostA = joinAsHost(meetingA);
        Who vicky = joinAsGuest(meetingA, "Vicky Kumar");
        Who rahul = joinAsGuest(meetingA, "../../Rahul\\..\\x");
        String meetingB = meeting("Daily/Standup", true);
        joinAsHost(meetingB);
        Who asha = joinAsGuest(meetingB, "आशा देवी");

        List<JsonNode> clips = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            clips.add(recordClip(vicky, 2));
        }
        for (int i = 0; i < 2; i++) {
            clips.add(recordClip(rahul, 3));
        }
        clips.add(recordClip(asha, 1));
        clips.add(recordClip(hostA, 1));

        String today = jdbc.queryForObject("select to_char(now() at time zone 'UTC', 'DD-MM-YYYY')", String.class);
        Path vickyDir = ROOT.resolve(today).resolve("Q3_review_draft_final").resolve("Vicky_Kumar");
        Path rahulDir = ROOT.resolve(today).resolve("Q3_review_draft_final").resolve("Rahulx");
        Path ashaDir = ROOT.resolve(today).resolve("DailyStandup").resolve("आशा_देवी");
        assertThat(webmFiles(vickyDir)).hasSize(3);
        assertThat(webmFiles(rahulDir)).hasSize(2);
        assertThat(webmFiles(ashaDir)).hasSize(1);
        // Nothing outside the root, no leftovers.
        for (Path meetingDir : List.of(vickyDir.getParent(), ashaDir.getParent())) {
            try (Stream<Path> all = Files.walk(meetingDir)) {
                assertThat(all.filter(p -> p.toString().endsWith(".part"))).isEmpty();
            }
        }
        assertThat(Files.exists(ROOT.getParent().resolve("Rahul"))).isFalse();

        // File and database agree: every clip COMPLETED, its file exists with exactly the size recorded.
        for (JsonNode c : clips) {
            assertThat(c.get("status").asString()).isEqualTo("COMPLETED");
            assertThat(c.get("fileName").asString()).matches("\\d{12}_[0-9a-f]{6}\\.webm");
            assertThat(c.has("storagePath")).isFalse();
            String path = jdbc.queryForObject("select storage_path from speech_recordings where id = ?", String.class,
                    UUID.fromString(c.get("id").asString()));
            Path file = ROOT.resolve(path);
            assertThat(file).exists();
            assertThat(Files.size(file)).isEqualTo(c.get("fileSize").asLong());
            assertThat(path).endsWith("/" + c.get("fileName").asString());
        }
        // Clips recorded back to back (often within the same second) all have different files.
        Set<String> names = new HashSet<>();
        clips.forEach(c -> names.add(c.get("fileName").asString()));
        assertThat(names).hasSize(clips.size());
    }

    @Test
    void chunksMustArriveInOrderAndCompletionNeedsAVerifiedFile() throws Exception {
        String m = meeting("Rules", true);
        joinAsHost(m);
        Who g = joinAsGuest(m, "Guest Rules");

        JsonNode clip = start(g);
        String id = clip.get("id").asString();
        chunk(g, id, 0, WEBM_HEAD).andExpect(status().isOk());
        chunk(g, id, 0, WEBM_HEAD).andExpect(status().isOk()); // a retry: accepted, not appended twice
        chunk(g, id, 2, new byte[] {1}).andExpect(status().isConflict()); // chunk 1 is missing
        chunk(g, id, 1, new byte[] {7, 7}).andExpect(status().isOk());
        JsonNode done = finish(g, id, 2);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("fileSize").asLong()).isEqualTo(WEBM_HEAD.length + 2);
        assertThat(finish(g, id, 2).get("status").asString()).isEqualTo("COMPLETED"); // finishing again is harmless
        chunk(g, id, 2, new byte[] {1}).andExpect(status().isConflict()); // closed

        // The client says it sent 3 chunks but only 1 arrived: kept, but INCOMPLETE, never COMPLETED.
        JsonNode partial = start(g);
        chunk(g, partial.get("id").asString(), 0, WEBM_HEAD).andExpect(status().isOk());
        JsonNode p = finish(g, partial.get("id").asString(), 3);
        assertThat(p.get("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(p.get("failureReason").asString()).contains("1 of 3");

        // Not WebM: FAILED, and no file is left behind.
        JsonNode bogus = start(g);
        chunk(g, bogus.get("id").asString(), 0, "<html>".getBytes()).andExpect(status().isOk());
        assertThat(finish(g, bogus.get("id").asString(), 1).get("status").asString()).isEqualTo("FAILED");
        String path = jdbc.queryForObject("select storage_path from speech_recordings where id = ?", String.class,
                UUID.fromString(bogus.get("id").asString()));
        assertThat(ROOT.resolve(path)).doesNotExist();
        assertThat(ROOT.resolve(path + ".part")).doesNotExist();

        // Starting a new clip while one is open closes the open one (a reloaded browser).
        JsonNode open = start(g);
        chunk(g, open.get("id").asString(), 0, WEBM_HEAD).andExpect(status().isOk());
        start(g);
        assertThat(jdbc.queryForObject("select status from speech_recordings where id = ?", String.class,
                UUID.fromString(open.get("id").asString()))).isEqualTo("INCOMPLETE");
    }

    @Test
    void clipsAreOnlyRecordedWithAutomaticRecordingAndForParticipantsInTheSession() throws Exception {
        String off = meeting("No auto record", false);
        joinAsHost(off);
        Who g = joinAsGuest(off, "Guest Off");
        mvc.perform(g.auth(post("/api/v1/meetings/" + off + "/speech-recordings")).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", g.pid(), "format", "audio/webm"))))
                .andExpect(status().isConflict());

        String on = meeting("Auto record", true);
        joinAsHost(on);
        // Admitted but the media server's "joined" notice not processed yet (someone who speaks right after joining): may record.
        Who justAdmitted = guest(on, "Just Admitted");
        mvc.perform(justAdmitted.auth(post("/api/v1/meetings/" + on + "/speech-recordings")).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", justAdmitted.pid(), "format", "video/webm"))))
                .andExpect(status().isCreated());
        // Someone who left the session may not.
        Who left = joinAsGuest(on, "Left Already");
        mvc.perform(left.auth(post("/api/v1/meetings/" + on + "/leave")).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("participantId", left.pid())))).andExpect(status().is2xxSuccessful());
        mvc.perform(left.auth(post("/api/v1/meetings/" + on + "/speech-recordings")).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", left.pid(), "format", "video/webm"))))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(403, 409));
        // A file path or a format other than WebM in the request is refused, not used.
        Who connected = joinAsGuest(on, "Connected");
        mvc.perform(connected.auth(post("/api/v1/meetings/" + on + "/speech-recordings")).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", connected.pid(), "format", "../../etc/passwd"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theHostSeesItsMeetingsClipsAParticipantOnlyItsOwnAndNobodyElseAnything() throws Exception {
        String m = meeting("Access", true);
        Who h = joinAsHost(m);
        SignedIn alice = users.create(mvc, "Alice Speaker");
        Who a = joinAsUser(m, alice);
        Who guest = joinAsGuest(m, "Guest Bob");
        JsonNode aliceClip = recordClip(a, 1);
        JsonNode bobClip = recordClip(guest, 1);
        recordClip(h, 1);

        JsonNode hosted = list(host, "view=hosted&meetingId=" + m);
        assertThat(hosted.get("items").findValuesAsString("participantName"))
                .containsExactlyInAnyOrder("Alice Speaker", "Guest Bob", "Clip Host");
        assertThat(hosted.toString()).doesNotContain("storagePath").doesNotContain(ROOT.toString().replace("\\", "\\\\"));
        JsonNode mine = list(alice, "view=mine");
        assertThat(mine.get("items").findValuesAsString("participantName")).containsExactly("Alice Speaker");
        assertThat(list(alice, "view=hosted").get("items").size()).isZero();

        // Filters: participant, date.
        assertThat(list(host, "view=hosted&participantId=" + guest.pid()).get("items").findValuesAsString("participantName"))
                .containsExactly("Guest Bob");
        String today = jdbc.queryForObject("select to_char(now() at time zone 'UTC', 'YYYY-MM-DD')", String.class);
        assertThat(list(host, "view=hosted&meetingId=" + m + "&date=" + today).get("items").size()).isEqualTo(3);
        assertThat(list(host, "view=hosted&meetingId=" + m + "&date=2000-01-01").get("items").size()).isZero();
        // The dashboard counts the clips of the host's meetings and the space they use; Alice hosts none.
        JsonNode summary = json.readTree(mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", host.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(summary.get("speechClipCount").asLong()).isEqualTo(3);
        assertThat(summary.get("speechClipBytes").asLong()).isEqualTo(3L * WEBM_HEAD.length);
        JsonNode aliceSummary = json.readTree(mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", alice.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(aliceSummary.get("speechClipCount").asLong()).isZero();
        JsonNode facets = json.readTree(mvc.perform(get("/api/v1/speech-recordings/facets?view=hosted")
                .header("Authorization", host.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(facets.get("dates").toString()).contains(today);

        // Someone else: 404 for clips that exist (nothing revealed); a participant cannot reach another's clip.
        SignedIn mallory = users.create(mvc, "Mallory");
        mvc.perform(get("/api/v1/speech-recordings/" + bobClip.get("id").asString()).header("Authorization", mallory.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/speech-recordings/" + bobClip.get("id").asString() + "/access-url")
                .header("Authorization", alice.bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/speech-recordings")).andExpect(status().isUnauthorized());
        chunk(guest, aliceClip.get("id").asString(), 1, new byte[] {1}).andExpect(status().isNotFound());

        // Playing: a signed URL for an authorized user streams the file; tampering with it does not work.
        JsonNode access = json.readTree(mvc.perform(post("/api/v1/speech-recordings/" + aliceClip.get("id").asString()
                        + "/access-url").header("Authorization", alice.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        URI url = URI.create(access.get("url").asString());
        assertThat(url.toString()).doesNotContain(ROOT.toString()).doesNotContain(".webm/");
        MvcResult file = mvc.perform(get(url.getPath() + "?" + url.getQuery())).andExpect(status().isOk()).andReturn();
        assertThat(file.getResponse().getContentType()).startsWith("audio/webm");
        assertThat(file.getResponse().getContentAsByteArray()).startsWith(WEBM_HEAD);
        mvc.perform(get(url.getPath() + "?" + url.getQuery().replaceAll("sig=[^&]+", "sig=forged")))
                .andExpect(status().isForbidden());
        mvc.perform(get(url.getPath().replace(aliceClip.get("id").asString(), bobClip.get("id").asString()) + "?" + url.getQuery()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/speech-recordings/..%2F..%2Fetc%2Fpasswd/content?expires=1&sig=x"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(400, 403, 404));
    }

    @Test
    void clipsLeftOpenByAGoneBrowserOrARestartAreClosedButNeverMarkedCompleted() throws Exception {
        String m = meeting("Restart", true);
        joinAsHost(m);
        Who g = joinAsGuest(m, "Guest Restart");
        JsonNode withData = start(g);
        chunk(g, withData.get("id").asString(), 0, WEBM_HEAD).andExpect(status().isOk());
        JsonNode empty = start(g); // closes withData as INCOMPLETE; this one never gets a chunk
        jdbc.update("update speech_recordings set last_chunk_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))), UUID.fromString(empty.get("id").asString()));

        assertThat(service.recoverStale(Duration.ofMinutes(2))).isEqualTo(1);
        assertThat(clipStatus(empty)).isEqualTo("FAILED");
        assertThat(clipStatus(withData)).isEqualTo("INCOMPLETE");
        String path = jdbc.queryForObject("select storage_path from speech_recordings where id = ?", String.class,
                UUID.fromString(withData.get("id").asString()));
        assertThat(ROOT.resolve(path)).exists();
        assertThat(jdbc.queryForObject("select count(*) from speech_recordings where status = 'COMPLETED' and id in (?, ?)",
                Integer.class, UUID.fromString(empty.get("id").asString()), UUID.fromString(withData.get("id").asString())))
                .isZero();
    }

    @Test
    void aNameWithCharactersBeyondTheBasicPlaneIsRecordedAndOneBadClipNeverBlocksRecovery() throws Exception {
        // QA RC-1: a 60-unit cut used to split "𠀀" and leave a path Windows refuses, which then broke every recovery run.
        String m = meeting("Wide names", true);
        joinAsHost(m);
        Who wide = joinAsGuest(m, "a".repeat(59) + "𠀀 and more");
        JsonNode clip = recordClip(wide, 2);
        assertThat(clip.get("status").asString()).isEqualTo("COMPLETED");
        String path = jdbc.queryForObject("select storage_path from speech_recordings where id = ?", String.class,
                UUID.fromString(clip.get("id").asString()));
        assertThat(path).contains("/" + "a".repeat(59) + "𠀀/");
        assertThat(ROOT.resolve(path)).exists();

        // Two abandoned clips; one has a stored path that is not usable here. Each is closed on its own.
        Who g = joinAsGuest(m, "Guest Stale");
        JsonNode bad = start(g);
        jdbc.update("update speech_recordings set storage_path = ? where id = ?",
                "../outside/bad?name.webm", UUID.fromString(bad.get("id").asString()));
        Who g2 = joinAsGuest(m, "Guest Stale Two");
        JsonNode good = start(g2);
        chunk(g2, good.get("id").asString(), 0, WEBM_HEAD).andExpect(status().isOk());
        jdbc.update("update speech_recordings set last_chunk_at = ? where id in (?, ?)",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
                UUID.fromString(bad.get("id").asString()), UUID.fromString(good.get("id").asString()));

        assertThat(service.recoverStale(Duration.ofMinutes(2))).isEqualTo(2);
        assertThat(clipStatus(bad)).isEqualTo("FAILED");
        assertThat(clipStatus(good)).isEqualTo("INCOMPLETE");
    }

    // ------------------------------------------------------------------ helpers

    private String clipStatus(JsonNode clip) {
        return jdbc.queryForObject("select status from speech_recordings where id = ?", String.class,
                UUID.fromString(clip.get("id").asString()));
    }

    private static List<Path> webmFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".webm")).toList();
        }
    }

    private JsonNode recordClip(Who who, int chunks) throws Exception {
        JsonNode clip = start(who);
        String id = clip.get("id").asString();
        for (int i = 0; i < chunks; i++) {
            chunk(who, id, i, i == 0 ? WEBM_HEAD : new byte[] {(byte) i, 0, 1}).andExpect(status().isOk());
        }
        return finish(who, id, chunks);
    }

    private JsonNode start(Who who) throws Exception {
        return json.readTree(mvc.perform(who.auth(post("/api/v1/meetings/" + who.meetingId() + "/speech-recordings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("participantId", who.pid(), "format", "audio/webm;codecs=opus"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions chunk(Who who, String id, int seq, byte[] bytes) throws Exception {
        return mvc.perform(who.auth(put("/api/v1/meetings/" + who.meetingId() + "/speech-recordings/" + id + "/chunks/" + seq))
                .param("participantId", who.pid()).contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes));
    }

    private JsonNode finish(Who who, String id, int chunks) throws Exception {
        return json.readTree(mvc.perform(who.auth(post("/api/v1/meetings/" + who.meetingId() + "/speech-recordings/" + id + "/finish"))
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("participantId", who.pid(), "chunks", chunks))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode list(SignedIn user, String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/speech-recordings?" + query).header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String meeting(String title, boolean autoRecord) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", title, "type", "INSTANT", "timeZone", "UTC", "autoRecord", autoRecord))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private Who joinAsHost(String meetingId) throws Exception {
        return joinAsUser(meetingId, host);
    }

    private Who joinAsUser(String meetingId, SignedIn user) throws Exception {
        JsonNode j = json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                .header("Authorization", user.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        connected(j);
        return new Who(meetingId, j.get("participantId").asString(), null, user);
    }

    private Who guest(String meetingId, String name) throws Exception {
        JsonNode j = json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/join")
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("displayName", name))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return new Who(meetingId, j.get("participantId").asString(), j.get("waitToken").asString(), null);
    }

    private Who joinAsGuest(String meetingId, String name) throws Exception {
        Who g = guest(meetingId, name);
        JsonNode room = json.readTree(mvc.perform(get("/api/v1/meetings/" + meetingId + "/join-status")
                        .param("participantId", g.pid()).header(TOKEN, g.secret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        connected(room);
        return g;
    }

    private void connected(JsonNode join) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_" + UUID.randomUUID(),
                MediaWebhookEvent.Type.PARTICIPANT_JOINED, "participant_joined", join.get("roomName").asString(),
                join.get("participantId").asString(), null, Instant.now(), "PA_" + UUID.randomUUID()));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }
}
