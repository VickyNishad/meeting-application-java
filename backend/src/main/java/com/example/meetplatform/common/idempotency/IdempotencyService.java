package com.example.meetplatform.common.idempotency;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.util.Tokens;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Replays the stored response when a client retries a non-idempotent POST with the same
 * {@code Idempotency-Key}. A key reused with a different payload is rejected.
 *
 * <p>The key is reserved (unique insert, committed in its own transaction) before the action runs, so of
 * several concurrent requests with the same key exactly one executes; the others get
 * {@code 409 IDEMPOTENCY_IN_PROGRESS} until it finishes, and the stored response afterwards. A failed action
 * releases the key so the client can retry.
 */
@Service
public class IdempotencyService {

    private static final Duration RETENTION = Duration.ofHours(24);
    /** How long a reservation whose request died (e.g. instance crash) blocks the key. */
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(5);
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_\\-:.]{8,64}$");

    private final IdempotencyRecordRepository repository;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository repository, JsonMapper jsonMapper, TransactionTemplate tx,
                              Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.tx = tx;
        this.clock = clock;
    }

    public ResponseEntity<?> execute(String scope, String key, Object request, Supplier<ResponseEntity<?>> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        if (!KEY_FORMAT.matcher(key).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be 8-64 URL-safe characters.");
        }
        String requestHash = Tokens.sha256(jsonMapper.writeValueAsString(request));
        Optional<IdempotencyRecord> existing = reserve(scope, key, requestHash);
        if (existing.isPresent()) {
            return answer(existing.get(), requestHash);
        }

        ResponseEntity<?> response;
        try {
            response = action.get();
        } catch (RuntimeException | Error e) {
            release(scope, key);
            throw e;
        }
        if (response.getStatusCode().is2xxSuccessful()) {
            Instant now = clock.instant();
            String body = jsonMapper.writeValueAsString(response.getBody());
            tx.executeWithoutResult(s -> repository.findByScopeAndIdempotencyKey(scope, key)
                    .ifPresent(r -> r.complete(response.getStatusCode().value(), body, now.plus(RETENTION))));
        } else {
            release(scope, key);
        }
        return response;
    }

    /** Inserts a reservation for the key; returns the live record instead if the key is already taken. */
    private Optional<IdempotencyRecord> reserve(String scope, String key, String requestHash) {
        Instant now = clock.instant();
        try {
            return tx.execute(s -> {
                Optional<IdempotencyRecord> found = repository.findByScopeAndIdempotencyKey(scope, key);
                if (found.isPresent() && found.get().getExpiresAt().isAfter(now)) {
                    return found;
                }
                found.ifPresent(expired -> {
                    repository.delete(expired);
                    repository.flush();
                });
                repository.saveAndFlush(new IdempotencyRecord(scope, key, requestHash, now, now.plus(RESERVATION_TTL)));
                return Optional.<IdempotencyRecord>empty();
            });
        } catch (DataIntegrityViolationException concurrentReservation) {
            // Another request inserted the same key first (unique constraint): answer from its record.
            return Optional.of(repository.findByScopeAndIdempotencyKey(scope, key)
                    .orElseThrow(IdempotencyService::inProgress));
        }
    }

    private static ResponseEntity<?> answer(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new ApiException(ErrorCode.CONFLICT, "Idempotency-Key was already used with a different request.");
        }
        if (!record.isCompleted()) {
            throw inProgress();
        }
        return ResponseEntity.status(record.getResponseStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", "true")
                .body(record.getResponseBody());
    }

    private void release(String scope, String key) {
        tx.executeWithoutResult(s -> repository.findByScopeAndIdempotencyKey(scope, key)
                .filter(r -> !r.isCompleted())
                .ifPresent(repository::delete));
    }

    private static ApiException inProgress() {
        return new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "A request with this Idempotency-Key is still being processed. Retry shortly.");
    }

    @Scheduled(cron = "0 15 * * * *")
    public void purgeExpired() {
        tx.executeWithoutResult(s -> repository.deleteExpired(clock.instant()));
    }
}
