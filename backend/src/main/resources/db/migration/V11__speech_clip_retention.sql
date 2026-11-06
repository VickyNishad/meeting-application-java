-- Speaking clips kept in S3 are removed after their retention period. The row stays as the record that the clip
-- existed, marked deleted, so a clip is never listed or played once its object is gone. Additive only.
ALTER TABLE speech_recordings ADD COLUMN deleted_at timestamptz;

CREATE INDEX ix_speech_recordings_retention ON speech_recordings (start_time) WHERE deleted_at IS NULL;
