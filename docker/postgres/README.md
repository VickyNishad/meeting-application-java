# PostgreSQL (Docker)

The `postgres` service of [docker-compose.yml](../../docker-compose.yml): PostgreSQL 17 with data in the named
volume `meet-platform_pgdata`, reachable from this PC at `localhost` on `POSTGRES_PORT` (default 5432; set 5433
in `.env` if another PostgreSQL already uses 5432) and from other containers at `postgres:5432`.

## init/

Every `*.sql` / `*.sh` file in `init/` is mounted into `/docker-entrypoint-initdb.d/` and runs **once, in name
order, when the data volume is created for the first time**. On an existing volume nothing in here runs again,
even after `docker compose down` / `up`. To apply a changed init script, either run it by hand (see
README-Docker.md, section 9, "Running a SQL script") or recreate the volume (README-Docker.md, section 12, "Cleaning up and resetting
safely" — this deletes the data).

Do not create application tables here: the API creates and migrates its schema with Flyway
(`backend/src/main/resources/db/migration`) on startup. A table created here would conflict with a migration.

## Why PostgreSQL 17 and not 18

The stack has run on 17 so far and the `pgdata` volume holds a 17 data directory. PostgreSQL cannot open a data
directory of another major version: moving to 18 requires a dump and restore (see README-Docker.md, "Upgrading
PostgreSQL"), so it is a deliberate step, not an image tag change. The postgres:18 images also expect the volume
at `/var/lib/postgresql` instead of `/var/lib/postgresql/data`; the upgrade steps include that change.
