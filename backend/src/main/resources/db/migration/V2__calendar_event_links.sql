-- External calendar event created for a booking in the host's connected calendar (Google / Microsoft),
-- so it can be removed again when the booking is cancelled or rescheduled.
ALTER TABLE bookings
    ADD COLUMN external_calendar_provider varchar(32),
    ADD COLUMN external_event_id          varchar(512);
