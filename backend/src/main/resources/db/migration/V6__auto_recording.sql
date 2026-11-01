-- Automatic recording: a per-meeting switch that records the whole room plus one file per participant
-- (that participant's own camera and microphone; screen shares are only in the room recording). A participant
-- recording belongs to its subject: the signed-in user it shows can play it; guests' recordings are the host's.
ALTER TABLE meetings ADD COLUMN auto_record boolean NOT NULL DEFAULT false;

-- auto_suppressed: a moderator stopped this auto-started recording, so auto-record must not start it again
-- (for the room session, or for that participant) until auto-record is switched off and on again.
ALTER TABLE recordings
    ADD COLUMN participant_id  uuid REFERENCES meeting_participants (id) ON DELETE SET NULL,
    ADD COLUMN subject_user_id uuid REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN subject_name    varchar(120),
    ADD COLUMN auto_started    boolean NOT NULL DEFAULT false,
    ADD COLUMN auto_suppressed boolean NOT NULL DEFAULT false;

ALTER TABLE recordings DROP CONSTRAINT ck_recordings_kind;
ALTER TABLE recordings ADD CONSTRAINT ck_recordings_kind CHECK (kind IN ('CLOUD', 'PARTICIPANT'));

-- at most one in-flight room recording per session, and one in-flight recording per participant
DROP INDEX ux_recordings_active_room;
CREATE UNIQUE INDEX ux_recordings_active_room ON recordings (room_id)
    WHERE kind = 'CLOUD' AND status IN ('STARTING', 'ACTIVE', 'STOPPING');
CREATE UNIQUE INDEX ux_recordings_active_participant ON recordings (participant_id)
    WHERE kind = 'PARTICIPANT' AND status IN ('STARTING', 'ACTIVE', 'STOPPING');
CREATE INDEX ix_recordings_subject_user ON recordings (subject_user_id) WHERE subject_user_id IS NOT NULL;
