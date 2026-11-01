-- Runs ONCE, when the PostgreSQL data volume (pgdata) is created for the first time, as POSTGRES_USER against
-- POSTGRES_DB. It never runs again on an existing database: to re-run it the volume must be recreated (see
-- README-Docker.md, section 12, "Cleaning up and resetting safely").
--
-- The application schema is NOT created here: the Spring Boot API owns it through Flyway migrations
-- (backend/src/main/resources/db/migration), applied when the API starts. Keep this file to database-level
-- settings that Flyway does not manage.

-- Store and compare timestamps in UTC for every new session of this database (the API also works in UTC).
DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I SET timezone TO %L', current_database(), 'UTC');
END
$$;
