package com.example.meetplatform.recording.service;

import com.example.meetplatform.common.scheduling.JobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Finishes speaking clips nobody finished: the browser closed or lost its connection mid-clip, or the server
 * restarted while clips were being recorded. Runs at startup and then periodically, on one instance at a time; a clip
 * counts as abandoned once no chunk has arrived for {@code app.speech-recordings.stale-after}.
 */
@Component
public class SpeechRecordingRecovery {

    private static final Logger log = LoggerFactory.getLogger(SpeechRecordingRecovery.class);

    private final SpeechRecordingService service;
    private final JobLock jobLock;
    private final Duration staleAfter;

    public SpeechRecordingRecovery(SpeechRecordingService service, JobLock jobLock,
                                   @Value("${app.speech-recordings.stale-after:PT2M}") Duration staleAfter) {
        this.service = service;
        this.jobLock = jobLock;
        this.staleAfter = staleAfter;
    }

    /** Never fails the startup: a clip that cannot be closed now is retried by the periodic run. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            recover();
        } catch (RuntimeException e) {
            log.error("Could not close unfinished speaking clips at startup; retrying periodically", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.speech-recordings.recovery-interval:PT1M}",
            initialDelayString = "${app.speech-recordings.recovery-interval:PT1M}")
    public void recover() {
        jobLock.runExclusively("speech-recording-recovery", Duration.ofMinutes(5), () -> {
            int n = service.recoverStale(staleAfter);
            if (n > 0) {
                log.info("Closed {} speaking clip(s) that were not finished", n);
            }
        });
    }

    /** Daily: speaking clips past their retention (S3 storage only). */
    @Scheduled(cron = "${app.speech-recordings.retention-cron:0 45 3 * * *}")
    public void enforceRetention() {
        jobLock.runExclusively("speech-recording-retention", Duration.ofMinutes(30), () -> {
            SpeechRecordingService.RetentionRun run = service.deleteExpired();
            if (run.failed() > 0 || run.skipped() > 0) {
                log.warn("Speaking clip retention: {} removed, {} failed, {} skipped; these are tried again on the next run",
                        run.deleted(), run.failed(), run.skipped());
            } else if (run.deleted() > 0) {
                log.info("Speaking clip retention: {} removed", run.deleted());
            }
        });
    }
}
