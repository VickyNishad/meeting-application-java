-- Visibility modes renamed: EVERYONE -> EVERYONE_VISIBLE, HOSTS_ONLY -> HOST_AND_SELF_ONLY (the API still accepts the
-- old names). HOST_AND_SELF_ONLY sessions now use two media rooms; nothing else about stored data changes.
ALTER TABLE meetings DROP CONSTRAINT ck_meetings_visibility;
ALTER TABLE meetings ALTER COLUMN visibility_mode TYPE varchar(24);
UPDATE meetings SET visibility_mode = 'EVERYONE_VISIBLE' WHERE visibility_mode = 'EVERYONE';
UPDATE meetings SET visibility_mode = 'HOST_AND_SELF_ONLY' WHERE visibility_mode = 'HOSTS_ONLY';
ALTER TABLE meetings ALTER COLUMN visibility_mode SET DEFAULT 'EVERYONE_VISIBLE';
ALTER TABLE meetings ADD CONSTRAINT ck_meetings_visibility
    CHECK (visibility_mode IN ('EVERYONE_VISIBLE', 'HOST_AND_SELF_ONLY'));

ALTER TABLE recordings ALTER COLUMN visibility_mode TYPE varchar(24);
UPDATE recordings SET visibility_mode = 'EVERYONE_VISIBLE' WHERE visibility_mode = 'EVERYONE';
UPDATE recordings SET visibility_mode = 'HOST_AND_SELF_ONLY' WHERE visibility_mode = 'HOSTS_ONLY';
