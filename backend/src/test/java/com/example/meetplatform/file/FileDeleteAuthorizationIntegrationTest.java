package com.example.meetplatform.file;

import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may delete a shared file, by the state of their participation (QA finding F1). An uploader keeps the right after
 * leaving, a moderator needs to be in the meeting now, and anyone denied or removed has neither.
 */
class FileDeleteAuthorizationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    private SignedIn host;
    private SignedIn alice;
    private SignedIn bob;
    private String meetingId;
    private String hostPid;
    private String alicePid;
    private String bobPid;

    @BeforeEach
    void meetingWithThreePeople() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        host = users.create(mvc, "Auth Host");
        alice = users.create(mvc, "Auth Alice");
        bob = users.create(mvc, "Auth Bob");
        meetingId = meeting(host);
        hostPid = join(meetingId, host).get("participantId").asString();
        alicePid = join(meetingId, alice).get("participantId").asString();
        bobPid = join(meetingId, bob).get("participantId").asString();
    }

    @Test
    void anActiveUploaderDeletesTheirOwnFile() throws Exception {
        String file = upload(alice, alicePid);

        deleteAs(alice, alicePid, file).andExpect(status().isNoContent());
        assertGone(file);
    }

    @Test
    void anActiveModeratorDeletesSomeoneElsesFile() throws Exception {
        String file = upload(alice, alicePid);

        deleteAs(host, hostPid, file).andExpect(status().isNoContent());
        assertGone(file);
    }

    @Test
    void aPromotedCoHostInTheMeetingDeletesSomeoneElsesFile() throws Exception {
        String file = upload(alice, alicePid);
        promote(bobPid);

        deleteAs(bob, bobPid, file).andExpect(status().isNoContent());
        assertGone(file);
    }

    @Test
    void anUploaderWhoLeftCanStillDeleteTheirOwnFile() throws Exception {
        String file = upload(alice, alicePid);
        leave(alice, alicePid);

        deleteAs(alice, alicePid, file).andExpect(status().isNoContent());
        assertGone(file);
    }

    @Test
    void anUploaderCanDeleteTheirOwnFileAfterTheMeetingEnded() throws Exception {
        String file = upload(alice, alicePid);
        end();

        deleteAs(alice, alicePid, file).andExpect(status().isNoContent());
        assertGone(file);
    }

    @Test
    void aModeratorCannotDeleteOthersFilesAfterTheMeetingEndedButKeepsTheirOwn() throws Exception {
        String theirs = upload(alice, alicePid);
        String own = upload(host, hostPid);
        end();

        refused(deleteAs(host, hostPid, theirs));
        verify(storage, never()).delete(anyString(), anyString());
        deleteAs(host, hostPid, own).andExpect(status().isNoContent());
    }

    @Test
    void aModeratorWhoLeftCannotDeleteOthersFiles() throws Exception {
        String file = upload(alice, alicePid);
        promote(bobPid);
        leave(bob, bobPid);

        refused(deleteAs(bob, bobPid, file));
        verify(storage, never()).delete(anyString(), anyString());
        assertListed(file);
    }

    @Test
    void aRemovedUploaderCannotDeleteTheirOwnFile() throws Exception {
        String file = upload(alice, alicePid);
        remove(alicePid);

        refused(deleteAs(alice, alicePid, file));
        verify(storage, never()).delete(anyString(), anyString());
        assertListed(file);
    }

    @Test
    void aRemovedModeratorCannotDeleteAnyFile() throws Exception {
        String file = upload(alice, alicePid);
        promote(bobPid);
        String own = upload(bob, bobPid);
        remove(bobPid);

        refused(deleteAs(bob, bobPid, file));
        refused(deleteAs(bob, bobPid, own));
        verify(storage, never()).delete(anyString(), anyString());
        assertListed(file);
    }

    /** No API path leads to this state for someone who uploaded, so the row is set directly. */
    @Test
    void aDeniedUploaderCannotDeleteTheirOwnFile() throws Exception {
        String file = upload(alice, alicePid);
        setState(alicePid, "DENIED");

        refused(deleteAs(alice, alicePid, file));
        verify(storage, never()).delete(anyString(), anyString());
    }

    /** No API path leads to this state for a moderator, so the row is set directly. */
    @Test
    void aDeniedModeratorCannotDeleteOthersFiles() throws Exception {
        String file = upload(alice, alicePid);
        promote(bobPid);
        setState(bobPid, "DENIED");

        refused(deleteAs(bob, bobPid, file));
        verify(storage, never()).delete(anyString(), anyString());
    }

    @Test
    void aParticipantWhoNeverGotInCannotDelete() throws Exception {
        String file = upload(alice, alicePid);
        jdbc.update("update meeting_participants set state = 'WAITING', admitted_at = null where id = ?",
                UUID.fromString(alicePid));

        refused(deleteAs(alice, alicePid, file));
        verify(storage, never()).delete(anyString(), anyString());
    }

    /**
     * A new session gives the same person a new row, so removal must also stop them using an older one. Without this
     * check the older row, still LEFT, would pass.
     */
    @Test
    void removalOnALaterRowRevokesAnEarlierRowOfTheSamePerson() throws Exception {
        String file = upload(alice, alicePid);
        leave(alice, alicePid);
        String columns = jdbc.queryForObject("""
                select string_agg(quote_ident(column_name), ',') from information_schema.columns
                where table_name = 'meeting_participants' and column_name not in ('id', 'state', 'created_at')
                """, String.class);
        jdbc.update("insert into meeting_participants (id, state, created_at, " + columns + ") "
                + "select gen_random_uuid(), 'REMOVED', now() + interval '1 minute', " + columns
                + " from meeting_participants where id = ?", UUID.fromString(alicePid));

        refused(deleteAs(alice, alicePid, file));
        verify(storage, never()).delete(anyString(), anyString());
    }

    @Test
    void aParticipantCannotDeleteAnotherPersonsFile() throws Exception {
        String file = upload(alice, alicePid);

        refused(deleteAs(bob, bobPid, file));
        verify(storage, never()).delete(anyString(), anyString());
    }

    @Test
    void anotherMeetingsFileCannotBeDeletedThroughThisMeeting() throws Exception {
        String file = upload(alice, alicePid);
        String other = meeting(host);
        String otherPid = join(other, host).get("participantId").asString();

        mvc.perform(delete("/api/v1/meetings/" + other + "/files/" + file).param("participantId", otherPid)
                .header("Authorization", host.bearer())).andExpect(status().isNotFound());
        // A participant of this meeting cannot be used in the path of the other one either.
        refused(mvc.perform(delete("/api/v1/meetings/" + other + "/files/" + file).param("participantId", alicePid)
                .header("Authorization", alice.bearer())));
        verify(storage, never()).delete(anyString(), anyString());
    }

    @Test
    void forgedParticipantAndFileIdentifiersAreRefused() throws Exception {
        String file = upload(alice, alicePid);

        // Bob names Alice's participant id, or the host's, and is not either of them.
        refused(deleteAs(bob, alicePid, file));
        refused(deleteAs(bob, hostPid, file));
        // A participant id that does not exist, and a file id that does not exist.
        refused(deleteAs(bob, UUID.randomUUID().toString(), file));
        deleteAs(bob, bobPid, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        // No credentials, and a wrong participant secret.
        refused(mvc.perform(delete(path(file)).param("participantId", alicePid)));
        refused(mvc.perform(delete(path(file)).param("participantId", alicePid).header("X-Participant-Token", "wrong")));
        verify(storage, never()).delete(anyString(), anyString());
    }

    @Test
    void someoneRemovedLearnsNothingAboutWhichFilesExist() throws Exception {
        remove(alicePid);

        // The same answer for a file id that does not exist as for one that does.
        refused(deleteAs(alice, alicePid, UUID.randomUUID().toString()));
    }

    @Test
    void deletingTwiceRemovesTheObjectOnce() throws Exception {
        String file = upload(alice, alicePid);

        deleteAs(alice, alicePid, file).andExpect(status().isNoContent());
        deleteAs(alice, alicePid, file).andExpect(status().isNotFound());
        verify(storage, times(1)).delete(anyString(), anyString());
    }

    private ResultActions deleteAs(SignedIn user, String participantId, String fileId) throws Exception {
        return mvc.perform(delete(path(fileId)).param("participantId", participantId)
                .header("Authorization", user.bearer()));
    }

    private static void refused(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden());
    }

    private String path(String fileId) {
        return "/api/v1/meetings/" + meetingId + "/files/" + fileId;
    }

    private void assertGone(String fileId) throws Exception {
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/files").param("participantId", hostPid)
                        .header("Authorization", host.bearer()))
                .andExpect(jsonPath("$[?(@.id == '" + fileId + "')]").doesNotExist());
    }

    private void assertListed(String fileId) throws Exception {
        mvc.perform(get("/api/v1/meetings/" + meetingId + "/files").param("participantId", hostPid)
                        .header("Authorization", host.bearer()))
                .andExpect(jsonPath("$[?(@.id == '" + fileId + "')]").exists());
    }

    private void promote(String participantId) throws Exception {
        mvc.perform(post(moderation(participantId) + "/role").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("role", "CO_HOST"))))
                .andExpect(status().is2xxSuccessful());
    }

    private void remove(String participantId) throws Exception {
        mvc.perform(post(moderation(participantId) + "/remove").header("Authorization", host.bearer()))
                .andExpect(status().is2xxSuccessful());
    }

    private void leave(SignedIn user, String participantId) throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/leave").header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("participantId", participantId))))
                .andExpect(status().is2xxSuccessful());
    }

    private void end() throws Exception {
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/end").header("Authorization", host.bearer()))
                .andExpect(status().is2xxSuccessful());
    }

    private void setState(String participantId, String state) {
        jdbc.update("update meeting_participants set state = ? where id = ?", state, UUID.fromString(participantId));
    }

    private String moderation(String participantId) {
        return "/api/v1/meetings/" + meetingId + "/moderation/participants/" + participantId;
    }

    private String upload(SignedIn user, String participantId) throws Exception {
        MockMultipartFile text = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        return json.readTree(mvc.perform(multipart("/api/v1/meetings/" + meetingId + "/files").file(text)
                        .param("participantId", participantId).header("Authorization", user.bearer()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String meeting(SignedIn owner) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Files", "type", "INSTANT", "timeZone", "UTC"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private JsonNode join(String meeting, SignedIn user) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/meetings/" + meeting + "/join").header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
