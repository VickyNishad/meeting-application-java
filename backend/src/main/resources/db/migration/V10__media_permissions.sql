-- Host-controlled media permissions. Per meeting: who may publish the microphone and the camera (ALLOWED: everyone,
-- RESTRICTED: hosts and co-hosts only); screen sharing keeps its existing screen_share_policy. Per participation: an
-- optional override (ALLOWED / RESTRICTED, NULL = follow the meeting) for each of the three. Existing meetings keep
-- today's behaviour (everyone may unmute and start video).
ALTER TABLE meetings
    ADD COLUMN mic_permission    varchar(16) NOT NULL DEFAULT 'ALLOWED',
    ADD COLUMN camera_permission varchar(16) NOT NULL DEFAULT 'ALLOWED',
    ADD CONSTRAINT ck_meetings_mic_permission CHECK (mic_permission IN ('ALLOWED', 'RESTRICTED')),
    ADD CONSTRAINT ck_meetings_camera_permission CHECK (camera_permission IN ('ALLOWED', 'RESTRICTED'));

ALTER TABLE meeting_participants
    ADD COLUMN mic_override    varchar(16),
    ADD COLUMN camera_override varchar(16),
    ADD COLUMN screen_override varchar(16),
    ADD CONSTRAINT ck_participants_mic_override CHECK (mic_override IN ('ALLOWED', 'RESTRICTED')),
    ADD CONSTRAINT ck_participants_camera_override CHECK (camera_override IN ('ALLOWED', 'RESTRICTED')),
    ADD CONSTRAINT ck_participants_screen_override CHECK (screen_override IN ('ALLOWED', 'RESTRICTED'));
