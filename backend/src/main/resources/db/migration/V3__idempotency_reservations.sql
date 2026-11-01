-- Idempotency keys are now reserved before the action runs: a reserved (in-progress) record has no response
-- yet, so concurrent requests with the same key are answered 409 IDEMPOTENCY_IN_PROGRESS instead of running
-- the action twice. The response is filled in once the action succeeds.
ALTER TABLE idempotency_records
    ALTER COLUMN response_status DROP NOT NULL,
    ALTER COLUMN response_body DROP NOT NULL;
