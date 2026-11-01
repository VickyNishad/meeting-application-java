-- Webinar-style visibility, room-state persistence, active-speaker statistics and recording metadata.
-- Additive only: every new column has a default or is nullable, so existing rows stay valid.

-- EVERYONE: everyone sees and hears everyone. HOSTS_ONLY (webinar): attendees see and hear only the stage
-- (host and co-hosts); the stage sees everyone. Fixed while a session is live.
ALTER TABLE meetings ADD COLUMN visibility_mode varchar(16) NOT NULL DEFAULT 'EVERYONE';
ALTER TABLE meetings ADD CONSTRAINT ck_meetings_visibility CHECK (visibility_mode IN ('EVERYONE', 'HOSTS_ONLY'));

-- media_sid: the SFU connection (LiveKit participant SID) the row was last JOINED with, so a late
-- participant_left from an older connection cannot mark a reconnected participant as gone.
-- local_recording_started_at / hand_raised_at: room state late joiners and reconnecting clients must see.
-- masked: an attendee of a webinar session; their identity stays hidden from other non-stage viewers (participant
-- lists) even if the meeting's visibility mode changes later.
ALTER TABLE meeting_participants
    ADD COLUMN media_sid                  varchar(64),
    ADD COLUMN local_recording_started_at timestamptz,
    ADD COLUMN hand_raised_at             timestamptz,
    ADD COLUMN masked                     boolean NOT NULL DEFAULT false;

-- STAGE: written by an attendee of a webinar, visible to the stage and the sender only.
ALTER TABLE chat_messages ADD COLUMN audience varchar(8) NOT NULL DEFAULT 'ALL';
ALTER TABLE chat_messages ADD CONSTRAINT ck_chat_messages_audience CHECK (audience IN ('ALL', 'STAGE'));

ALTER TABLE recordings
    ADD COLUMN content_type         varchar(64),
    ADD COLUMN visibility_mode      varchar(16),
    ADD COLUMN stage_only           boolean NOT NULL DEFAULT false,
    ADD COLUMN last_egress_event_at timestamptz;

-- Speaking time per participant and session, reported by one present moderator's client.
CREATE TABLE speaker_stats (
    room_id        uuid        NOT NULL REFERENCES meeting_rooms (id) ON DELETE CASCADE,
    participant_id uuid        NOT NULL REFERENCES meeting_participants (id) ON DELETE CASCADE,
    meeting_id     uuid        NOT NULL REFERENCES meetings (id) ON DELETE CASCADE,
    speaking_ms    bigint      NOT NULL DEFAULT 0,
    turns          integer     NOT NULL DEFAULT 0,
    first_spoke_at timestamptz,
    last_spoke_at  timestamptz,
    updated_at     timestamptz NOT NULL,
    PRIMARY KEY (room_id, participant_id),
    CONSTRAINT ck_speaker_stats_positive CHECK (speaking_ms >= 0 AND turns >= 0)
);
CREATE INDEX ix_speaker_stats_meeting ON speaker_stats (meeting_id);

-- Replay protection for speaker reports: one row per (session, reporting participant, batch number).
CREATE TABLE speaker_stat_batches (
    room_id     uuid        NOT NULL REFERENCES meeting_rooms (id) ON DELETE CASCADE,
    reporter_id uuid        NOT NULL REFERENCES meeting_participants (id) ON DELETE CASCADE,
    batch_seq   bigint      NOT NULL,
    received_at timestamptz NOT NULL,
    PRIMARY KEY (room_id, reporter_id, batch_seq)
);

-- Plain indexes for lookups that only had partial indexes, and for FK columns with ON DELETE actions.
CREATE INDEX ix_recordings_room ON recordings (room_id);
CREATE INDEX ix_recordings_participant ON recordings (participant_id);
CREATE INDEX ix_recordings_started_by ON recordings (started_by);
CREATE INDEX ix_chat_messages_participant ON chat_messages (participant_id);
CREATE INDEX ix_file_metadata_uploader ON file_metadata (uploaded_by_participant_id);
