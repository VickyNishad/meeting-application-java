package com.example.meetplatform.recording;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.media.dto.MediaWebhookEvent;
import com.example.meetplatform.media.dto.MediaWebhookEvent.EgressStatus;
import com.example.meetplatform.media.dto.MediaWebhookEvent.EgressUpdate;
import com.example.meetplatform.recording.entity.Recording;
import com.example.meetplatform.recording.repository.RecordingRepository;
import com.example.meetplatform.recording.service.RecordingReconciler;
import com.example.meetplatform.recording.service.RecordingService;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recording lifecycle robustness: egress status only moves forward, failed captures expire like completed ones,
 * one undeletable file does not block retention, and recordings whose final webhook was lost are reconciled.
 */
class RecordingLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestUsers users;

    @Autowired
    RecordingRepository recordings;

    @Autowired
    RecordingService recordingService;

    @Autowired
    RecordingReconciler reconciler;

    @Autowired
    JdbcTemplate jdbc;

    private SignedIn host;

    @BeforeEach
    void setUp() throws Exception {
        when(media.issueToken(any())).thenReturn("tok");
        host = users.create(mvc, "Lifecycle Host");
    }

    /** SYNC-5: a delayed "active" after the stop never turns the recording (and the room indicator) back on. */
    @Test
    void lateActiveAfterStopIsIgnored() throws Exception {
        String egressId = "EG_" + UUID.randomUUID();
        Recording r = startManual(egressId);
        mvc.perform(post("/api/v1/meetings/" + r.getMeetingId() + "/recordings/" + r.getId() + "/stop")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isAccepted());

        egress(egressId, EgressStatus.ACTIVE, Instant.now());
        assertThat(reload(r).getStatus()).isEqualTo(Recording.Status.STOPPING);

        // An "ending" event, then an older "active" event delivered late: still stopping.
        egress(egressId, EgressStatus.ENDING, Instant.now());
        egress(egressId, EgressStatus.ACTIVE, Instant.now().minusSeconds(30));
        assertThat(reload(r).getStatus()).isEqualTo(Recording.Status.STOPPING);

        egress(egressId, EgressStatus.COMPLETE, Instant.now());
        assertThat(reload(r).getStatus()).isEqualTo(Recording.Status.AVAILABLE);
    }

    /** PRIV-4: a failed capture gets a retention date and its (partial) file is deleted when it expires. */
    @Test
    void failedRecordingsExpireAndTheirFileIsDeleted() throws Exception {
        String egressId = "EG_" + UUID.randomUUID();
        Recording r = startManual(egressId);
        egress(egressId, EgressStatus.FAILED, Instant.now());
        Recording failed = reload(r);
        assertThat(failed.getStatus()).isEqualTo(Recording.Status.FAILED);
        assertThat(failed.getRetentionUntil()).isAfter(Instant.now().plus(Duration.ofDays(1)));

        expire(r.getId());
        recordingService.enforceRetention();

        verify(storage).delete(anyString(), eq(r.getStorageKey()));
        assertThat(reload(r).getStatus()).isEqualTo(Recording.Status.DELETED);
    }

    /** QA-B7: a recording that fails while starting (possibly after egress began writing) also expires. */
    @Test
    void recordingThatFailsToStartGetsARetentionDate() throws Exception {
        String meetingId = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Fails", "type", "INSTANT", "timeZone", "UTC"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", host.bearer()))
                .andExpect(status().isOk());
        when(media.startRoomRecording(anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new ApiException(ErrorCode.MEDIA_PROVIDER_UNAVAILABLE, "timeout"));
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings").header("Authorization", host.bearer()))
                .andExpect(status().isServiceUnavailable());

        Recording failed = recordings.findAll().stream().filter(r -> r.getMeetingId().toString().equals(meetingId))
                .findFirst().orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(Recording.Status.FAILED);
        assertThat(failed.getRetentionUntil()).isNotNull()
                .isBefore(Instant.now().plus(Recording.FAILED_RETENTION).plusSeconds(60));
    }

    /** REC-2: a file that cannot be deleted now is retried tomorrow; the rest are still deleted. */
    @Test
    void oneUndeletableFileDoesNotBlockRetention() throws Exception {
        Recording stuck = completed(startManual("EG_" + UUID.randomUUID()));
        Recording fine = completed(startManual("EG_" + UUID.randomUUID()));
        expire(stuck.getId());
        expire(fine.getId());
        doThrow(new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "down"))
                .when(storage).delete(anyString(), eq(stuck.getStorageKey()));

        recordingService.enforceRetention();

        assertThat(reload(fine).getStatus()).isEqualTo(Recording.Status.DELETED);
        Recording retried = reload(stuck);
        assertThat(retried.getStatus()).isEqualTo(Recording.Status.AVAILABLE);
        assertThat(retried.getRetentionUntil()).isAfter(Instant.now().plus(Duration.ofHours(20)));
    }

    /** SYNC-4: recordings whose final webhook never arrived are completed or failed from the media server's state. */
    @Test
    void reconcilerRepairsRecordingsWhoseFinalWebhookWasLost() throws Exception {
        String unknown = "EG_" + UUID.randomUUID();
        String finished = "EG_" + UUID.randomUUID();
        String running = "EG_" + UUID.randomUUID();
        Recording lost = startManual(unknown);
        Recording done = startManual(finished);
        Recording live = startManual(running);
        for (Recording r : new Recording[] {lost, done, live}) {
            jdbc.update("UPDATE recordings SET status = 'STOPPING', updated_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))), r.getId());
        }
        when(media.egressStatus(unknown)).thenReturn(Optional.empty());
        when(media.egressStatus(finished)).thenReturn(Optional.of(new EgressUpdate(finished, EgressStatus.COMPLETE,
                null, "x.mp4", 4096L, 120L, Instant.now().minusSeconds(120), Instant.now())));
        when(media.egressStatus(running)).thenReturn(Optional.of(new EgressUpdate(running, EgressStatus.ACTIVE,
                null, null, null, null, Instant.now().minusSeconds(60), null)));

        reconciler.reconcile();

        assertThat(reload(lost).getStatus()).isEqualTo(Recording.Status.FAILED);
        Recording completed = reload(done);
        assertThat(completed.getStatus()).isEqualTo(Recording.Status.AVAILABLE);
        assertThat(completed.getSizeBytes()).isEqualTo(4096L);
        assertThat(reload(live).getStatus()).isEqualTo(Recording.Status.STOPPING);
    }

    // ------------------------------------------------------------------ helpers

    /** A manually started cloud recording in its own meeting (one in-flight recording per room). */
    private Recording startManual(String egressId) throws Exception {
        when(media.startRoomRecording(anyString(), anyString(), anyString(), anyBoolean())).thenReturn(egressId);
        String meetingId = json.readTree(mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Lifecycle", "type", "INSTANT", "timeZone", "UTC"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc.perform(post("/api/v1/meetings/" + meetingId + "/join").header("Authorization", host.bearer()))
                .andExpect(status().isOk());
        JsonNode started = json.readTree(mvc.perform(post("/api/v1/meetings/" + meetingId + "/recordings")
                        .header("Authorization", host.bearer()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        return recordings.findById(UUID.fromString(started.get("id").asString())).orElseThrow();
    }

    private Recording completed(Recording r) throws Exception {
        egress(r.getEgressId(), EgressStatus.COMPLETE, Instant.now());
        return reload(r);
    }

    private void expire(UUID recordingId) {
        jdbc.update("UPDATE recordings SET retention_until = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(1))), recordingId);
    }

    private Recording reload(Recording r) {
        return recordings.findById(r.getId()).orElseThrow();
    }

    private void egress(String egressId, EgressStatus status, Instant createdAt) throws Exception {
        when(media.parseWebhook(anyString(), anyString())).thenReturn(new MediaWebhookEvent("EV_" + UUID.randomUUID(),
                MediaWebhookEvent.Type.EGRESS_UPDATED, "egress_updated", null, null,
                new EgressUpdate(egressId, status, status == EgressStatus.FAILED ? "boom" : null, "r.mp4", 2048L, 60L,
                        Instant.now().minusSeconds(60), status == EgressStatus.COMPLETE ? Instant.now() : null),
                createdAt));
        mvc.perform(post("/api/v1/webhooks/livekit").contentType("application/webhook+json").content("{}")
                .header("Authorization", "signed")).andExpect(status().isOk());
    }
}
