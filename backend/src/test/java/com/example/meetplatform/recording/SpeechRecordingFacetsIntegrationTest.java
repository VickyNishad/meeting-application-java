package com.example.meetplatform.recording;

import com.example.meetplatform.recording.SpeechClipFlow.Who;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

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

    private JsonNode facets(String view) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/speech-recordings/facets?view=" + view)
                .header("Authorization", host.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode list(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/speech-recordings?view=hosted&" + query)
                .header("Authorization", host.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
