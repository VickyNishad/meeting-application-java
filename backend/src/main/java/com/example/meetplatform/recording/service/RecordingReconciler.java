package com.example.meetplatform.recording.service;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.scheduling.JobLock;
import com.example.meetplatform.media.dto.MediaWebhookEvent.EgressStatus;
import com.example.meetplatform.media.dto.MediaWebhookEvent.EgressUpdate;
import com.example.meetplatform.media.service.MediaService;
import com.example.meetplatform.recording.entity.Recording;
import com.example.meetplatform.recording.repository.RecordingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repairs recordings whose final egress webhook never arrived. Recordings that have looked "in progress" for a while
 * are checked against the media server: finished ones are completed, failed or unknown ones are marked failed (so the
 * room can record again and the indicator goes off). A recording the media server still reports as running is left
 * alone. The media server is asked outside any transaction; each recording is then updated in its own transaction.
 */
@Component
public class RecordingReconciler {

    private static final Logger log = LoggerFactory.getLogger(RecordingReconciler.class);
    static final Duration STALE_AFTER = Duration.ofMinutes(15);
    private static final int BATCH = 50;

    private final RecordingRepository recordings;
    private final RecordingService recordingService;
    private final MediaService media;
    private final JobLock jobLock;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RecordingReconciler(RecordingRepository recordings, RecordingService recordingService, MediaService media,
                               JobLock jobLock, TransactionTemplate tx, Clock clock) {
        this.recordings = recordings;
        this.recordingService = recordingService;
        this.media = media;
        this.jobLock = jobLock;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.recording.reconcile-interval:PT5M}",
            initialDelayString = "${app.recording.reconcile-initial-delay:PT2M}")
    public void scheduled() {
        jobLock.runExclusively("recording-reconcile", Duration.ofMinutes(10), this::reconcile);
    }

    /** @return how many recordings were changed */
    public int reconcile() {
        List<Candidate> stale = tx.execute(s -> recordings.findStale(clock.instant().minus(STALE_AFTER),
                        PageRequest.of(0, BATCH)).stream()
                .map(r -> new Candidate(r.getId(), r.getEgressId()))
                .toList());
        int changed = 0;
        for (Candidate c : stale == null ? List.<Candidate>of() : stale) {
            EgressUpdate state;
            if (c.egressId() == null) {
                // Never reached the media server (start failed before an egress id was stored).
                state = new EgressUpdate(null, EgressStatus.FAILED, "Recording never started", null, null, null,
                        null, null);
            } else {
                Optional<EgressUpdate> found;
                try {
                    found = media.egressStatus(c.egressId());
                } catch (ApiException e) {
                    log.debug("Cannot reconcile recording {} now: {}", c.id(), e.getMessage());
                    continue;
                }
                if (found.isPresent() && !isFinal(found.get().status())) {
                    continue; // still running on the media server
                }
                state = found.orElse(new EgressUpdate(c.egressId(), EgressStatus.FAILED,
                        "The media server no longer knows this recording", null, null, null, null, null));
            }
            Boolean applied = tx.execute(s -> recordings.findById(c.id())
                    .filter(Recording::isInFlight)
                    .map(r -> {
                        recordingService.applyEgressState(r, state, "RECONCILE");
                        return true;
                    })
                    .orElse(false));
            if (Boolean.TRUE.equals(applied)) {
                changed++;
                log.info("Reconciled recording {} as {}", c.id(), state.status());
            }
        }
        return changed;
    }

    private static boolean isFinal(EgressStatus status) {
        return status == EgressStatus.COMPLETE || status == EgressStatus.FAILED;
    }

    private record Candidate(UUID id, String egressId) {
    }
}
