# 07 — Setup and Deployment

## Local development

Prerequisites: JDK 21, Node 24, Docker (for the full stack).

```bash
cp .env.example .env            # fill in the CHANGE_ME values
docker compose up -d --build    # postgres, redis, s3, mailpit, livekit, egress, api, web
open http://localhost:3000      # web app;  http://localhost:8025 = e-mails (verification links)
```

Running the API and web outside Docker (faster iteration):

```bash
docker compose up -d postgres redis minio mailpit livekit egress
cd backend && ./mvnw spring-boot:run      # reads the same variables; export them from .env first
cd web && npm install && npm run dev      # http://localhost:5173, proxies /api to :8080
```

Windows note: if `JAVA_HOME` points at an older JDK, set it for the shell first,
e.g. `$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.12"`.

### Two-device smoke test (the vertical slice)
1. Set `LIVEKIT_NODE_IP` to the machine's LAN IP and `LIVEKIT_PUBLIC_URL=ws://<LAN-IP>:7880`; restart.
   (Browsers allow camera/mic only on HTTPS or `localhost`; for a second device use the HTTPS setup below or
   Chrome's `--unsafely-treat-insecure-origin-as-secure` for testing.)
2. Register a host, verify via Mailpit, create an instant meeting with the waiting room on, start it.
3. Open the join link on a second device as a guest → host admits → both see and hear each other.
4. Host: mute guest, request unmute (guest must accept), share screen, start/stop cloud recording
   (recording appears in MinIO bucket `meet-recordings` and in the Recording library), end meeting.
5. Check meeting history and the moderation audit trail.

## Production topology

```
Internet ── 443/tcp ──► Reverse proxy (TLS) ──► web (nginx, SPA) /api,/oauth2,/login/oauth2 ──► API replicas
        ── 443/tcp ──► LiveKit (wss signalling via proxy or its own TLS)        ┌─► PostgreSQL (primary + backups)
        ── 7881/tcp, 50000-60000/udp ──► LiveKit nodes (media)                  ├─► Redis
        ── 3478/udp, 5349/tcp (TURN) ──► LiveKit built-in TURN                   └─► S3 / object storage
                                         Egress workers ◄── Redis ──► LiveKit
```

### Reverse proxy (Caddy example)

```caddyfile
meet.example.com {
    encode zstd gzip
    @api path /api/* /oauth2/* /login/oauth2/* /v3/api-docs* /swagger-ui*
    handle @api { reverse_proxy api-1:8080 api-2:8080 { lb_policy least_conn  health_uri /actuator/health/readiness } }
    handle /actuator/* { respond 404 }
    handle { reverse_proxy web:80 }
}
media.example.com {
    reverse_proxy livekit:7880      # WebSocket signalling (wss://media.example.com)
}
```

With nginx, forward `Host`, `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host`, and allow WebSocket
upgrades for the media host. The API runs `server.forward-headers-strategy=native` and trusts forwarded headers
only from private-network proxies.

The Compose stack ships this as an opt-in service: `docker compose --profile tls up -d` starts Caddy with
`infra/caddy/Caddyfile` (web + API + LiveKit signalling on `SITE_DOMAIN`, presigned S3 URLs on `S3_DOMAIN`). The
file lists the matching `.env` values.

### LiveKit production settings
* `rtc.use_external_ip: true` (or explicit `node_ip`) so the public address is advertised. In Compose:
  `LIVEKIT_NODE_IP=<public IP>` or `LIVEKIT_USE_EXTERNAL_IP=true`.
* Open UDP `50000-50100` (the Compose range; `rtc.port_range_*` and the published ports must match) and TCP `7881`
  to each node; TCP is the fallback when UDP is blocked.
* Compose: `LIVEKIT_TURN_ENABLED=true` and `LIVEKIT_TURN_DOMAIN` enable the embedded TURN relay on UDP `3478`.
  TURN over TLS (below) additionally needs a certificate.
* Enable TURN: `turn.enabled: true`, `turn.domain: turn.example.com`, `turn.tls_port: 5349`, `turn.udp_port: 3478`,
  with a certificate for `turn.example.com` — needed for clients behind restrictive firewalls/proxies.
* Webhook URL: `https://meet.example.com/api/v1/webhooks/livekit` (or the internal API address).
* Egress: give each worker ≥ 4 vCPU / 4 GB; one room-composite recording needs ~2–4 vCPU.

### Environment
All configuration is environment-driven (`.env.example` lists every variable). Production must set:
`SPRING_PROFILES_ACTIVE=prod` (the Compose default), `APP_PUBLIC_URL`, `APP_CORS_ALLOWED_ORIGINS`, `APP_JWT_SECRET`, `APP_COOKIE_SECURE=true`,
`APP_OPENAPI_ENABLED=false` (the default), `APP_METRICS_SCRAPE_PASSWORD` (if Prometheus scrapes the API),
DB/Redis/S3/LiveKit/SMTP credentials, `LIVEKIT_PUBLIC_URL=wss://…`,
`S3_PUBLIC_ENDPOINT=https://…`.

### Speaking-clip storage (LOCAL / S3)
* `RECORDING_STORAGE_TYPE=LOCAL` (development, Windows): clips stay in `RECORDING_LOCAL_PATH`
  (`D:/MeetingApplication/Recordings`) as `DD-MM-YYYY/<Meeting>/<Participant>/DDMMYYHHmmss_<id>.webm`.
* `RECORDING_STORAGE_TYPE=S3` (production, the `prod` profile's default): `RECORDING_LOCAL_PATH` is a staging folder;
  each verified clip is uploaded to `s3://$AWS_S3_BUCKET/$AWS_S3_PREFIX/<same path>` before it is marked COMPLETED.
  An upload failure keeps it RECORDING and the recovery job retries every minute. Playback redirects to presigned URLs.
* Bucket: create it yourself (never created by the API), "Block all public access" on, default encryption on.
  Role/user policy: `s3:PutObject`, `s3:GetObject` on `arn:aws:s3:::<bucket>/<prefix>/*`, `s3:ListBucket` on the
  bucket. Prefer an IAM role (EC2 instance profile with IMDSv2 hop limit 2 under Docker, ECS task role, EKS IRSA)
  and leave `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` empty.
* Switching an existing installation from LOCAL to S3: earlier clips only exist on disk and would 404 on playback.
  Copy them once (the layout is identical), then switch:
  `aws s3 sync <RECORDING_LOCAL_PATH> s3://<bucket>/<prefix> --exclude "*.part"`.

### Health and readiness
* Liveness: `GET /actuator/health/liveness`
* Readiness: `GET /actuator/health/readiness` (database + Redis)
* Metrics: `GET /actuator/prometheus` (internal only, and HTTP Basic `prometheus:$APP_METRICS_SCRAPE_PASSWORD` —
  the endpoint is closed while that variable is unset); LiveKit metrics on `:6789`.
* `docker compose --profile observability up -d` starts Prometheus (alert rules in `infra/prometheus/alerts.yml`),
  Grafana (`:3001`) and Jaeger (`:16686`).

### Kubernetes
Not required for the initial target. Introduce it when you need more than a handful of API replicas or automated
LiveKit node scaling: run the API as a Deployment with HPA on CPU and p95 latency, LiveKit with host networking
(one pod per node, UDP range open), Egress as a Deployment scaled on queue depth, and managed PostgreSQL/Redis/S3.

## Backup and restore

| Data | Backup | Restore |
|---|---|---|
| PostgreSQL | Nightly `pg_dump -Fc` + continuous WAL archiving (e.g. pgBackRest/WAL-G) for point-in-time recovery; keep 30 days | `pg_restore -d meet dump.file` or PITR to a timestamp; then start the API (Flyway validates the schema) |
| Object storage | Bucket versioning + replication to a second region/provider (`mc mirror` or provider replication) | Re-point `S3_ENDPOINT` or mirror back |
| Redis | Not authoritative (rate-limit counters, LiveKit coordination) — AOF only for fast restart | None needed |
| Secrets | Secret manager with versioning | |

### Docker Compose on Windows: data on the D: drive

- `DATA_ROOT` (`.env`, default `./docker`, git-ignored; e.g. `D:/meetly/docker` or `/srv/meetly`) holds `minio/` (recordings and shared files), `redis/` and `backups/`. Folders are created on first start. Prometheus and Grafana keep named volumes (their containers run as non-root users).
- PostgreSQL stays in the named volume `pgdata`: a data directory bind-mounted from NTFS fails on ownership/fsync. To keep it on D: as well, set Docker Desktop > Settings > Resources > Advanced > *Disk image location* to a folder on D: before the first `docker compose up`.
- The `pg-backup` service writes `DATA_ROOT/backups/meet-YYYYMMDD-HHMMSS.dump` once a day and keeps 14 days. Restore into the running stack:

  ```powershell
  Get-Content -AsByteStream .\docker\backups\meet-20261030-030000.dump | docker compose exec -T postgres pg_restore -U meet -d meet --clean --if-exists
  ```
- Set `APP_METRICS_SCRAPE_PASSWORD`: the API serves `/actuator/prometheus` only to that credential, and Prometheus reads it as a compose secret.

Example:

```bash
docker compose exec -T postgres pg_dump -U meet -Fc meet > backup-$(date +%F).dump
docker compose exec -T postgres pg_restore -U meet -d meet --clean --if-exists < backup-2026-10-27.dump
```

Test a restore into a scratch database monthly; record the recovery time.

## Upgrades
Rolling-update API replicas (stateless). Flyway migrations must stay backward compatible for one release
(expand → migrate → contract). LiveKit nodes are drained before replacement; rooms on a node end when it stops.
