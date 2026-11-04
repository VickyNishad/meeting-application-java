package com.example.meetplatform.recording;

import com.example.meetplatform.recording.SpeechClipFlow.Who;
import com.example.meetplatform.recording.entity.SpeechRecording;
import com.example.meetplatform.recording.repository.SpeechRecordingRepository;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Meeting-wise participant filter: each participant filter value carries the meeting of its participation
 * (speech_recordings.participant_id -> meeting_participants), only participants with clips are offered, and the list
 * filter combines meeting and participant.
 */
class SpeechRecordingFacetsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    @Autowired
    SpeechRecordingRepository speechRecordings;

    private SignedIn host;
    private SpeechClipFlow flow;

    @BeforeEach
    void setUp() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        host = users.create(mvc, "Facets Host");
        flow = new SpeechClipFlow(mvc, json, media, host);
    }

    @Test
    void participantOptionsCarryTheirMeetingAndOnlyParticipantsWithClipsAreOffered() throws Exception {
        String a = flow.meeting("Meeting A");
        String b = flow.meeting("Meeting B");
        flow.joinAsHost(a);
        flow.joinAsHost(b);
        Who vickyA = flow.joinAsGuest(a, "Vicky");
        Who rahulA = flow.joinAsGuest(a, "Rahul");
        Who silentA = flow.joinAsGuest(a, "Silent Sam"); // joined, never spoke: no clip
        Who vickyB = flow.joinAsGuest(b, "Vicky");
        Who ashaB = flow.joinAsGuest(b, "Asha");
        flow.recordClip(vickyA, 1);
        flow.recordClip(rahulA, 1);
        flow.recordClip(rahulA, 1);
        flow.recordClip(vickyB, 1);
        flow.recordClip(ashaB, 1);

        JsonNode facets = facets("hosted");
        List<String> participants = new ArrayList<>();
        facets.get("participants").forEach(p -> participants.add(
                p.get("id").asString() + "|" + p.get("label").asString() + "|" + p.get("meetingId").asString()));
        assertThat(participants).containsExactlyInAnyOrder(
                vickyA.pid() + "|Vicky|" + a,
                rahulA.pid() + "|Rahul|" + a,
                vickyB.pid() + "|Vicky|" + b,
                ashaB.pid() + "|Asha|" + b);
        assertThat(participants).noneMatch(p -> p.startsWith(silentA.pid()));
        // Meetings and dates are unchanged in shape.
        assertThat(facets.get("meetings")).hasSize(2);
        facets.get("meetings").forEach(m -> assertThat(m.has("meetingId")).isFalse());
        assertThat(facets.get("dates")).hasSize(1);
    }

    @Test
    void meetingAndParticipantFiltersTogetherReturnOnlyTheMatchingClips() throws Exception {
        String a = flow.meeting("Combo A");
        String b = flow.meeting("Combo B");
        flow.joinAsHost(a);
        flow.joinAsHost(b);
        Who rahulA = flow.joinAsGuest(a, "Rahul");
        Who vickyA = flow.joinAsGuest(a, "Vicky");
        Who ashaB = flow.joinAsGuest(b, "Asha");
        flow.recordClip(rahulA, 1);
        flow.recordClip(rahulA, 1);
        flow.recordClip(vickyA, 1);
        flow.recordClip(ashaB, 1);

        JsonNode rahulInA = list("meetingId=" + a + "&participantId=" + rahulA.pid());
        assertThat(rahulInA.get("totalItems").asLong()).isEqualTo(2);
        rahulInA.get("items").forEach(c -> {
            assertThat(c.get("meetingId").asString()).isEqualTo(a);
            assertThat(c.get("participantId").asString()).isEqualTo(rahulA.pid());
        });
        // A participant of another meeting matches nothing (the stale combination the UI now resets).
        assertThat(list("meetingId=" + b + "&participantId=" + rahulA.pid()).get("totalItems").asLong()).isZero();
        assertThat(list("meetingId=" + a).get("totalItems").asLong()).isEqualTo(3);
        assertThat(list("participantId=" + ashaB.pid()).get("totalItems").asLong()).isEqualTo(1);
    }

    @Test
    void theMineViewCarriesTheMeetingOfEachOwnParticipation() throws Exception {
        String a = flow.meeting("Mine A");
        String b = flow.meeting("Mine B");
        Who hostA = flow.joinAsHost(a);
        Who hostB = flow.joinAsHost(b);
        flow.recordClip(hostA, 1);
        flow.recordClip(hostB, 1);

        List<String> own = new ArrayList<>();
        facets("mine").get("participants").forEach(p -> own.add(p.get("id").asString() + "|" + p.get("meetingId").asString()));
        assertThat(own).containsExactlyInAnyOrder(hostA.pid() + "|" + a, hostB.pid() + "|" + b);
    }

    @Test
    void theHostIsFlaggedFromTheMeetingsHostAccountNotFromTheName() throws Exception {
        String a = flow.meeting("Host A");
        Who hostA = flow.joinAsHost(a);
        Who sameName = flow.joinAsGuest(a, "Facets Host"); // a guest with the host's display name
        Who rahul = flow.joinAsGuest(a, "Rahul");
        flow.recordClip(hostA, 1);
        flow.recordClip(sameName, 1);
        flow.recordClip(rahul, 1);

        Map<String, JsonNode> byId = participantsById(facets("hosted"));
        assertThat(byId.get(hostA.pid()).get("host").asBoolean()).isTrue();
        assertThat(byId.get(hostA.pid()).get("label").asString()).isEqualTo("Facets Host"); // the plain name
        assertThat(byId.get(sameName.pid()).get("host").asBoolean()).isFalse();
        assertThat(byId.get(rahul.pid()).get("host").asBoolean()).isFalse();
    }

    @Test
    void eachMeetingFlagsItsOwnHost() throws Exception {
        String own = flow.meeting("Own meeting");
        Who meInOwn = flow.joinAsHost(own);
        flow.recordClip(meInOwn, 1);
        // Another user's meeting, where this user is an ordinary participant.
        SignedIn other = users.create(mvc, "Other Host");
        SpeechClipFlow otherFlow = new SpeechClipFlow(mvc, json, media, other);
        String theirs = otherFlow.meeting("Their meeting");
        Who otherHost = otherFlow.joinAsHost(theirs);
        Who meInTheirs = flow.joinAsHost(theirs); // joins with this user's account; not the host there
        otherFlow.recordClip(otherHost, 1);
        flow.recordClip(meInTheirs, 1);

        Map<String, JsonNode> mine = participantsById(facets("mine"));
        assertThat(mine.get(meInOwn.pid()).get("host").asBoolean()).isTrue();
        assertThat(mine.get(meInTheirs.pid()).get("host").asBoolean()).isFalse();
        Map<String, JsonNode> theirsHosted = participantsById(facetsOf(other, "hosted"));
        assertThat(theirsHosted.get(otherHost.pid()).get("host").asBoolean()).isTrue();
        assertThat(theirsHosted.get(meInTheirs.pid()).get("host").asBoolean()).isFalse();
    }

    /**
     * Folder dates are the clip's start in the meeting's time zone (SpeechClipStorageTest covers the conversion), so
     * the clips are stored here with known folder dates, including one at 00:30 in Kolkata (still the previous day
     * in UTC).
     */
    @Test
    void meetingsAndParticipantsCarryTheirFolderDatesAndTheListFiltersByTheSameDates() throws Exception {
        String d = flow.meeting("Date meeting");
        String e = flow.meeting("Other day meeting");
        Who p1 = flow.joinAsGuest(d, "Asha");
        Who p2 = flow.joinAsGuest(d, "Late Larry");
        Who p3 = flow.joinAsGuest(e, "Early Eve");
        store(d, p1, "Date meeting", "01-11-2026", "2026-11-01T10:00:00Z");
        store(d, p1, "Date meeting", "02-11-2026", "2026-11-02T10:00:00Z");
        store(d, p2, "Date meeting (renamed)", "03-11-2026", "2026-11-02T19:00:00Z"); // 03-11 00:30 in Kolkata
        store(e, p3, "Other day meeting", "01-11-2026", "2026-11-01T09:00:00Z");

        JsonNode facets = facets("hosted");
        Map<String, JsonNode> meetings = new HashMap<>();
        facets.get("meetings").forEach(m -> meetings.put(m.get("id").asString(), m));
        assertThat(meetings).hasSize(2); // one entry per meeting, even after a rename
        assertThat(meetings.get(d).get("label").asString()).isEqualTo("Date meeting (renamed)"); // the latest title
        assertThat(dates(meetings.get(d))).containsExactly("2026-11-03", "2026-11-02", "2026-11-01");
        assertThat(dates(meetings.get(e))).containsExactly("2026-11-01");
        Map<String, JsonNode> people = participantsById(facets);
        assertThat(dates(people.get(p1.pid()))).containsExactly("2026-11-02", "2026-11-01");
        assertThat(dates(people.get(p2.pid()))).containsExactly("2026-11-03");
        assertThat(dates(people.get(p3.pid()))).containsExactly("2026-11-01");
        assertThat(dates(facets)).containsExactly("2026-11-03", "2026-11-02", "2026-11-01");

        // The list uses the same folder dates: the 00:30 clip belongs to the 3rd, not to its UTC date.
        assertThat(participantIds(list("date=2026-11-03"))).containsExactly(p2.pid());
        assertThat(participantIds(list("date=2026-11-02"))).containsExactly(p1.pid());
        assertThat(participantIds(list("date=2026-11-01&meetingId=" + d))).containsExactly(p1.pid());
        assertThat(list("date=2026-11-03&meetingId=" + e).get("totalItems").asLong()).isZero();
    }

    private void store(String meetingId, Who who, String title, String folderDate, String start) {
        String name = "x" + UUID.randomUUID().toString().substring(0, 6);
        SpeechRecording clip = new SpeechRecording(UUID.randomUUID(), UUID.fromString(meetingId), null,
                UUID.fromString(who.pid()), null, host.id(), title, "P", name + ".webm",
                folderDate + "/M/P/" + name + ".webm", "audio/webm", Instant.parse(start));
        clip.complete(10, Instant.parse(start).plusSeconds(2), 2000);
        speechRecordings.saveAndFlush(clip);
    }

    private static List<String> dates(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.get("dates").forEach(x -> out.add(x.asString()));
        return out;
    }

    private static Map<String, JsonNode> participantsById(JsonNode facets) {
        Map<String, JsonNode> out = new HashMap<>();
        facets.get("participants").forEach(p -> out.put(p.get("id").asString(), p));
        return out;
    }

    private static List<String> participantIds(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(c -> out.add(c.get("participantId").asString()));
        return out;
    }

    private JsonNode facets(String view) throws Exception {
        return facetsOf(host, view);
    }

    private JsonNode facetsOf(SignedIn who, String view) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/speech-recordings/facets?view=" + view)
                .header("Authorization", who.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode list(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/speech-recordings?view=hosted&" + query)
                .header("Authorization", host.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
