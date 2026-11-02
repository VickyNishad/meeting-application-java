# Running the Meeting platform with Docker (Windows)

This guide sets up the development environment with Docker Desktop: PostgreSQL, pgAdmin and, when you want it,
the whole application (API, web app, LiveKit media server, Redis, object storage, mail catcher).

> **Two ways to run locally.** `start-local.ps1` runs everything natively without Docker (see README.md). This
> guide is the Docker way. Both can be installed side by side, but each PostgreSQL needs its own port: if
> something on Windows already uses 5432 (for example the PostgreSQL started by `start-local.ps1`), set
> `POSTGRES_PORT=5433` in `.env`.

Every command in this guide works the same in **Command Prompt (cmd)** and **PowerShell** unless a block says
otherwise. Paste one line at a time.

---

## 1. Prerequisites

| Need | Why |
|---|---|
| Windows 10 22H2 or Windows 11, 64-bit, virtualization enabled in the BIOS | Docker Desktop runs Linux containers in a WSL 2 virtual machine |
| **Administrator rights, once** | Installing WSL 2 and Docker Desktop needs them. Afterwards Docker runs as a normal user who is in the `docker-users` group |
| 8 GB RAM minimum (16 GB recommended for the full stack) | Postgres + API + LiveKit + egress |
| ~20 GB free disk | Images and volumes |
| JDK 21 + Maven wrapper (already in `backend/`), Node 20+ | Only to build or run code outside containers |

## 2. Install Docker Desktop (with WSL 2)

1. **Find your account name.** In a normal (not elevated) terminal, as the person who will use Docker:
   ```
   whoami
   ```
   It prints something like `CORP\jdoe`. You need it in step 4: an administrator's own terminal would print the
   administrator's name instead.
2. **Install WSL 2** in a terminal opened with *Run as administrator*:
   ```
   wsl --install
   ```
   Restart Windows when asked. Afterwards `wsl --status` must show `Default Version: 2` and `wsl -l -v` a
   distribution. If Windows Update is blocked for WSL by your organisation, `wsl --update --web-download`
   downloads the kernel directly.
3. **Install Docker Desktop** from the official page,
   <https://docs.docker.com/desktop/setup/install/windows-install/>. Keep **"Use WSL 2 instead of Hyper-V"**
   checked.
4. **Add the account from step 1 to `docker-users`** (administrator terminal; type the real name, do not use
   variables such as `%USERNAME%`, which would name the administrator):
   ```
   net localgroup docker-users "CORP\jdoe" /add
   ```
   PowerShell alternative: `Add-LocalGroupMember -Group docker-users -Member "CORP\jdoe"`.
   The membership only takes effect after that user **signs out and signs in again** (or restarts Windows).
5. Start Docker Desktop. In *Settings > Resources > WSL integration* enable integration with your distribution
   if you also want to use `docker` from inside WSL. Wait until the status bar says **Engine running**.
6. Verify in a new terminal:
   ```
   docker --version
   docker compose version
   docker info
   wsl --status
   ```
   All must succeed. `docker info` failing with `pipe/docker_engine` means the engine is not running.

### Resources

With the WSL 2 backend, CPU and memory come from the WSL virtual machine, not from Docker Desktop's sliders.
Create the file `%UserProfile%\.wslconfig` (comments must be on their own lines):

```ini
[wsl2]
# 6GB is enough for postgres + pgadmin only; use 8-10GB for the full stack
memory=8GB
processors=4
swap=4GB
```

then run `wsl --shutdown` and restart Docker Desktop.

- **Disk:** *Settings > Resources > Advanced > Disk image location*. Move it to `D:\` to keep PostgreSQL data off
  the system drive (the database volume lives in that disk image).
- **File sharing:** the compose file bind-mounts `DATA_ROOT` (default `./docker` next to `docker-compose.yml`) for
  backups, Redis, MinIO and recordings. These folders are git-ignored. Set `DATA_ROOT` in `.env` to put them
  elsewhere (e.g. `D:/meetly/docker`).

## 3. What is in the project

```
Meeting/
├── docker-compose.yml          all services (postgres, pgadmin, redis, minio, mailpit, livekit, egress, backend, web, ...)
├── .env.example                every setting with placeholders  -> copy to .env
├── .env                        your local secrets (never committed; .gitignore excludes it)
├── docker/
│   ├── postgres/init/01-init.sql   runs once when the database volume is created
│   ├── postgres/README.md
│   ├── pgadmin/servers.json        pre-registers the database in pgAdmin
│   └── app/README.md               where the application Dockerfiles are
├── backend/Dockerfile          Spring Boot API image (JDK 21 build, JRE 21 runtime, non-root)
├── web/Dockerfile              web app image (nginx)
├── scripts/docker-*.bat        start / stop / restart / status / logs / build / clean
└── README-Docker.md            this file
```

The application images keep their Dockerfile and `.dockerignore` next to the code they build (`backend/`, `web/`),
so there is no extra Dockerfile in the project root.

## 4. Configure `.env`

```
copy .env.example .env
notepad .env
```

Replace **every** `CHANGE_ME` value with its own random secret. Generate one with a cryptographically secure
generator (PowerShell):

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

The database settings:

| Variable | Default | Meaning |
|---|---|---|
| `POSTGRES_DB` | `meet` | Database name (the API's database) |
| `POSTGRES_USER` | `meet` | Owner/login |
| `POSTGRES_PASSWORD` | – | Required |
| `POSTGRES_PORT` | `5432` | Port on this PC, bound to 127.0.0.1 only. Use `5433` if 5432 is taken |
| `PGADMIN_DEFAULT_EMAIL` | `admin@domain.com` | pgAdmin login |
| `PGADMIN_DEFAULT_PASSWORD` | – | Required |
| `PGADMIN_PORT` | `5050` | pgAdmin on this PC |

`POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` are only read when the database volume is **created**.
Changing them later does not rename the database or change the password: do that with SQL (section 9), and update
`docker/pgadmin/servers.json` if you change the name or user. The examples below use the defaults `meet` / `meet`.

## 5. Start, stop, restart

The scripts work from any folder (also from a network share) and check that `.env` exists before calling Docker.
They pause at the end only when started by double-click in Explorer; set `NOPAUSE=1` to never pause.

| Task | Script | Same as |
|---|---|---|
| Database + pgAdmin only | `scripts\docker-start.bat postgres pgadmin` | `docker compose up -d postgres pgadmin` |
| Everything | `scripts\docker-start.bat` | `docker compose up -d` |
| Stop (keeps all data) | `scripts\docker-stop.bat` | `docker compose stop` |
| Restart | `scripts\docker-restart.bat backend` | `docker compose restart backend` |
| Status / health | `scripts\docker-status.bat` | `docker compose ps -a` |
| Logs | `scripts\docker-logs.bat postgres` | `docker compose logs --tail 200 postgres` |
| Rebuild app images | `scripts\docker-build.bat` | `docker compose build` |
| Free disk space | `scripts\docker-clean.bat` | menu, see section 12 |

Startup order is handled by Compose: pgAdmin, the backup job and the API wait until PostgreSQL's health check
passes. The check connects over TCP (`pg_isready -h 127.0.0.1`), so it only passes once other containers can
really connect, also on the very first start while the database is being initialised. The web app waits for the
API's readiness check.

Full stack URLs: web app <http://localhost:3000>, mail <http://localhost:8025>, pgAdmin <http://localhost:5050>.

### Upgrading from an earlier version of docker-compose.yml

This version gives the stack's network a fixed name, `meet-platform-net` (it used to be `meet-platform_default`).
If containers from the earlier file exist, run this **once** before starting with the new file, so that every
container moves to the new network together:

```
docker compose down
```

`down` removes containers and the old network only. **Volumes are kept** (the database, pgAdmin settings);
never add `-v`, which would delete them.

## 6. pgAdmin

1. Open <http://localhost:5050> and sign in with `PGADMIN_DEFAULT_EMAIL` / `PGADMIN_DEFAULT_PASSWORD`.
2. Expand **Servers > Meeting DB (docker)** and enter `POSTGRES_PASSWORD` (tick *Save password*).

The server is registered with **Host `postgres`, port 5432**: pgAdmin runs in a container on the same Docker
network, where the database is reached by its service name. `localhost` inside the pgAdmin container is pgAdmin
itself, so it would not work.

`servers.json` is imported only the first time pgAdmin starts with an empty settings volume. To add it manually:
*Register > Server*, Connection tab: host `postgres`, port `5432`, database `meet`, user `meet`.

## 7. Connecting from your tools (outside Docker)

| Tool | Connection |
|---|---|
| IntelliJ / DBeaver / VS Code | host `localhost`, port `POSTGRES_PORT` (default 5432), database `meet`, user `meet` |
| Spring Boot run from the IDE | `DB_URL=jdbc:postgresql://localhost:5432/meet` (your `POSTGRES_PORT`), `DB_USERNAME`, `DB_PASSWORD` |
| Spring Boot in Docker (`backend` service) | `jdbc:postgresql://postgres:5432/meet`, set by docker-compose.yml |

`psql` without installing anything:

```
docker compose exec postgres psql -U meet -d meet
```

## 8. The Spring Boot container

`backend/Dockerfile` builds with Maven on JDK 21 and runs the layered jar on a JRE 21 Alpine image as the non-root
user `app`. Java runs as PID 1 (exec-form `ENTRYPOINT`), so `docker compose stop` sends it SIGTERM and Spring
shuts down gracefully. Memory: `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` of the container limit (2 GB in
docker-compose.yml). The API listens on 8080 inside the network only; the web container proxies `/api`.

Profiles: the backend runs the `prod` Spring profile unless `.env` sets `SPRING_PROFILES_ACTIVE` (empty = no
profile, which honours `APP_COOKIE_SECURE` / `APP_OPENAPI_ENABLED` for plain-http local use). Do not use `dev` in
Docker: it stores files on the container's local disk instead of MinIO.

Health: `docker compose ps` shows `backend` as *healthy* once `/actuator/health/readiness` is UP (it checks the
database).

## 9. Database administration

Open a SQL shell:

```
docker compose exec postgres psql -U meet -d meet
```

A new database with its own owner:

```sql
CREATE ROLE analytics_owner LOGIN PASSWORD 'a-strong-password';
CREATE DATABASE analytics OWNER analytics_owner;
```

A read-only login for the API's database (`meet`):

```sql
CREATE ROLE reporting LOGIN PASSWORD 'another-strong-password';
-- Every role may connect by default; granted explicitly in case PUBLIC's CONNECT was revoked.
GRANT CONNECT ON DATABASE meet TO reporting;
GRANT USAGE ON SCHEMA public TO reporting;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO reporting;
-- Tables the API creates later (Flyway migrations run as meet) become readable too.
ALTER DEFAULT PRIVILEGES FOR ROLE meet IN SCHEMA public GRANT SELECT ON TABLES TO reporting;
```

Other useful statements:

```sql
-- Change the application password (then update POSTGRES_PASSWORD in .env and restart the backend)
ALTER ROLE meet PASSWORD 'new-password';

-- Who is connected
SELECT pid, usename, application_name, client_addr, state, now() - query_start AS running_for, left(query, 80)
FROM pg_stat_activity WHERE datname = current_database();

-- Size
SELECT pg_size_pretty(pg_database_size(current_database()));
```

### Running a SQL script

Copy the file into the container, then run it there (no `<` redirection, which PowerShell does not support):

```
docker compose cp .\script.sql postgres:/tmp/script.sql
docker compose exec postgres psql -U meet -d meet -v ON_ERROR_STOP=1 -f /tmp/script.sql
docker compose exec postgres rm /tmp/script.sql
```

### Health

```
docker compose exec postgres pg_isready -h 127.0.0.1 -U meet -d meet
docker inspect --format "{{.State.Health.Status}}" meet-platform-postgres-1
```

### Backup

The `pg-backup` service writes a dump every 24 hours to `DATA_ROOT\backups` (default `.\docker\backups`, git-ignored; kept 14 days). A manual
backup: dump inside the container, then copy the file out. This never passes binary data through the terminal, so
the file is the same in cmd and PowerShell (`>` in Windows PowerShell 5.1 would corrupt it).

```
docker compose exec postgres pg_dump -U meet -d meet -Fc -f /tmp/meet-backup.dump
docker compose cp postgres:/tmp/meet-backup.dump .\meet-backup.dump
docker compose exec postgres rm /tmp/meet-backup.dump
```

### Restore

Stop the API first so nothing writes during the restore:

```
docker compose stop backend
docker compose cp .\meet-backup.dump postgres:/tmp/meet-backup.dump
docker compose exec postgres pg_restore -U meet -d meet --clean --if-exists --no-owner /tmp/meet-backup.dump
docker compose exec postgres rm /tmp/meet-backup.dump
docker compose start backend
```

To restore into a new database instead: `CREATE DATABASE meet_restore OWNER meet;` then use `-d meet_restore`.

### Upgrading PostgreSQL (e.g. 17 to 18)

The data volume is tied to its major version, and the postgres:18 image keeps its data in a different place:
the volume must be mounted at `/var/lib/postgresql` (not `/var/lib/postgresql/data` as for 17).

1. Back up (above) and keep the file safe.
2. `docker compose stop backend pg-backup pgadmin`
3. Delete the database volume: `scripts\docker-clean.bat`, option 4.
4. In docker-compose.yml change both `postgres:17-alpine` images (postgres and pg-backup) to `postgres:18-alpine`,
   and the postgres volume line to `- pgdata:/var/lib/postgresql`.
5. `scripts\docker-start.bat postgres`, wait for *healthy*, restore (above), then `scripts\docker-start.bat`.

Check first that the API's Flyway version supports the new PostgreSQL version.

### Speaking-clip files

Speaking clips (one `.webm` per participant per speaking turn) are plain files, not in the database: the backend
writes them to `RECORDING_STORAGE_PATH`, which docker-compose.yml sets to `/data/recordings` and bind-mounts from the
host folder `DATA_ROOT/Recordings` (default `./docker/Recordings`). They survive rebuilding, recreating and
restarting containers, and `docker compose down -v` does not touch them (it is not a Docker volume). Layout:
`DD-MM-YYYY<Meeting_Title><Participant_Name>DDMMYYHHmmss_<id>.webm`. Back them up by copying that folder; the
database keeps their metadata (`speech_recordings` table) and a database backup alone does not contain the media.

## 10. Logs and inspection

Follow one service's log (Ctrl+C stops following):

```
scripts\docker-logs.bat postgres -f
```

The last 10 minutes of the API's log:

```
docker compose logs --since 10m backend
```

Processes in each container, then live CPU and memory use:

```
docker compose top
docker stats
```

Full details of a container, the project's volumes, and its network:

```
docker inspect meet-platform-postgres-1
docker volume ls
docker network inspect meet-platform-net
```

Container logs rotate at 10 MB x 3 files per container.

## 11. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `failed to connect to the docker API at npipe:////./pipe/docker_engine` | Docker Desktop is not running. Start it and wait for *Engine running* |
| `permission denied` / access denied on the docker pipe | Your account is not in `docker-users`, or you have not signed out and in since it was added (section 2, step 4) |
| `docker: unknown command: docker compose` | Compose V2 plugin missing: reinstall or update Docker Desktop |
| `WSL 2 installation is incomplete` / no distributions | Run `wsl --install` as administrator, restart |
| `Bind for 127.0.0.1:5432 failed: port is already allocated` | Another PostgreSQL uses it: set `POSTGRES_PORT=5433` in `.env` |
| `required variable POSTGRES_PASSWORD is missing` | `.env` missing or the value is empty |
| pgAdmin: *could not connect to server* with host `localhost` | Use host `postgres` (section 6) |
| pgAdmin login rejected after changing `PGADMIN_DEFAULT_PASSWORD` | Only read on the first start: delete the pgAdmin volume (section 12, option 5) |
| Password authentication failed after changing `POSTGRES_PASSWORD` | Only read when the volume was created: `ALTER ROLE` (section 9) or reset the volume |
| init script changes have no effect | They run only for a new volume (docker/postgres/README.md) |
| `backend` stays *starting* / *unhealthy* | `scripts\docker-logs.bat backend`; usually a wrong secret in `.env` |
| Backend cannot resolve `postgres` after updating this file | Containers on two networks: run `docker compose down` once (section 5, "Upgrading") |

## 12. Cleaning up and resetting safely

`scripts\docker-clean.bat` asks what to remove, one item at a time:

1. stopped containers of this project
2. dangling images
3. unused networks
4. **the PostgreSQL volume**: only after you type `DELETE DATABASE`
5. the pgAdmin settings volume: only after you type `DELETE PGADMIN`

It must be run interactively: without keyboard input (a pipe, a scheduled task) or after three invalid choices it
exits without deleting anything. Nothing else ever deletes volumes. `docker compose down` removes containers and
the network but **keeps** volumes. Do not use `docker compose down -v` unless you mean to delete every volume of
the project, database included.

Full reset of the database: back up (section 9), run `docker-clean.bat` > 4, then `docker-start.bat`: the empty
database is initialised again (init scripts run), and the API recreates the schema with Flyway on start.

## 13. Optional tools

| Tool | Use it for | How |
|---|---|---|
| pgAdmin 4 | Browsing tables, running SQL, query plans | Included (`pgadmin` service) |
| PostgreSQL client tools (`psql`, `pg_dump`, `pg_restore`) | Scripts, backups | Use them inside the container (`docker compose exec postgres ...`); no local install needed |
| DBeaver Community | A desktop SQL client for many databases | Install on Windows; connect to `localhost` and your `POSTGRES_PORT` |
| Redis | Already part of the stack (`redis` service): the API's shared counters and locks | – |
| RedisInsight | Inspecting Redis keys | Desktop app; connect to `localhost:6379` with `REDIS_PASSWORD` |
| Portainer | A web UI for containers, if you prefer it to Docker Desktop's | `docker run -d -p 127.0.0.1:9443:9443 -v //var/run/docker.sock:/var/run/docker.sock portainer/portainer-ce` (not in the compose file on purpose) |
| Adminer | A one-file alternative to pgAdmin | Not needed alongside pgAdmin |

Optional tools stay out of docker-compose.yml so a normal start runs only what the application needs.

## 14. Security notes and production

This setup is for local development:

- `.env` holds real secrets: it is in `.gitignore`; only `.env.example` (placeholders) is shared.
- PostgreSQL, pgAdmin, Redis, the API (8080), MinIO (9000, 9001) and the other admin ports are bound to
  `127.0.0.1`. Reachable from the network are only what browsers need: the web app (3000) and LiveKit (7880, 7881,
  UDP 50000-50100, TURN UDP 3478). Set `S3_BIND_ADDRESS=0.0.0.0` if other devices must download recordings
  directly from MinIO.
- The backend runs the `prod` Spring profile by default. The local `.env` sets `SPRING_PROFILES_ACTIVE=` (empty)
  so that plain-http cookies and Swagger work on this PC.
- Runtime data (`docker/backups`, `docker/redis`, `docker/minio`, `docker/Recordings`) is git-ignored: database
  dumps contain user data and must never be committed.
- All services share one private bridge network (`meet-platform-net`) and talk by service name.
- The API container runs as the non-root user `app`. Other images run as their image's default user (nginx and
  MinIO as root). No container runs `privileged`, but the `egress` recording worker is granted the `SYS_ADMIN`
  capability, which its headless Chrome needs for its sandbox.
- Cleanup never deletes data on its own (section 12).

For production additionally: a managed or dedicated PostgreSQL with TLS, regular tested backups and point-in-time
recovery; secrets from a secret manager instead of `.env`; no pgAdmin (or pgAdmin behind VPN/SSO only); HTTPS in
front of the web app and `wss://` for LiveKit (`docker compose --profile tls up -d` adds Caddy for this, see
`infra/caddy/Caddyfile`); `LIVEKIT_NODE_IP` set to the public IP and `LIVEKIT_TURN_ENABLED=true`; image tags pinned
and scanned; resource limits sized from load tests. See docs/07-deployment.md.
