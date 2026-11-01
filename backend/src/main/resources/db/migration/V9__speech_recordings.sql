-- Speaking clips: one file per participant per speaking turn, recorded by that participant's own browser and filed
-- by the server under <RECORDING_STORAGE_PATH>/DD-MM-YYYY/<Meeting_Title>/<Participant_Name>/DDMMYYHHmmss_<id>.webm.
-- Additive only: the composite recordings in "recordings" are unchanged.
--
-- Column names follow this schema's conventions: "id" is the recording id, "duration_ms" the duration (milliseconds),
-- "status" the recording status. storage_path is relative to the storage root and never leaves the server.
CREATE TABLE speech_recordings (
    id                  uuid PRIMARY KEY,
    meeting_id          uuid         NOT NULL REFERENCES meetings (id),
    room_id             uuid REFERENCES meeting_rooms (id) ON DELETE SET NULL,
    participant_id      uuid         NOT NULL REFERENCES meeting_participants (id),
    -- The signed-in account of the participant (null for guests): who may see it as "my recording".
    participant_user_id uuid REFERENCES users (id) ON DELETE SET NULL,
    host_id             uuid         NOT NULL REFERENCES users (id),
    meeting_title       varchar(200) NOT NULL,
    participant_name    varchar(120) NOT NULL,
    file_name           varchar(64)  NOT NULL,
    storage_path        varchar(600) NOT NULL,
    file_format         varchar(64)  NOT NULL,
    file_size           bigint       NOT NULL DEFAULT 0,
    start_time          timestamptz  NOT NULL,
    end_time            timestamptz,
    duration_ms         bigint,
    status              varchar(16)  NOT NULL,
    failure_reason      varchar(500),
    chunks_received     integer      NOT NULL DEFAULT 0,
    last_chunk_at       timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_speech_recordings_status CHECK (status IN ('RECORDING', 'COMPLETED', 'INCOMPLETE', 'FAILED')),
    -- Two clips never share a file.
    CONSTRAINT ux_speech_recordings_storage_path UNIQUE (storage_path)
);

CREATE INDEX ix_speech_recordings_host ON speech_recordings (host_id, start_time DESC);
CREATE INDEX ix_speech_recordings_participant_user ON speech_recordings (participant_user_id, start_time DESC)
    WHERE participant_user_id IS NOT NULL;
CREATE INDEX ix_speech_recordings_meeting ON speech_recordings (meeting_id, start_time DESC);
-- The recovery job looks for clips still being recorded (after a restart or a client that never finished).
CREATE INDEX ix_speech_recordings_recording ON speech_recordings (last_chunk_at) WHERE status = 'RECORDING';
-- At most one clip being recorded per participant at a time.
CREATE UNIQUE INDEX ux_speech_recordings_one_active ON speech_recordings (participant_id) WHERE status = 'RECORDING';
