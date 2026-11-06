# 07 — Setup and Deployment

## Local development

Prerequisites: JDK 21, Node 24, Docker (for the full stack).

```bash
cp .env.example .env            # fill in the CHANGE_ME values
docker compose up -d --build    # postgres, redis, livekit, api, web (files on local disk)
open http://localhost:3000      # web app; verification links arrive by e-mail through your SMTP server
```

Running the API and web outside Docker (faster iteration):

```bash
docker compose up -d postgres redis livekit
cd backend && ./mvnw spring-boot:run      # reads the same variables; export them from .env first
cd web && npm install && npm run dev      # http://localhost:5173, proxies /api to :8080
```

Windows note: if `JAVA_HOME` points at an older JDK, set it for the shell first,
e.g. `$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.12"`.

### Two-device smoke test (the vertical slice)
1. Set `LIVEKIT_NODE_IP` to the machine's LAN IP and `LIVEKIT_PUBLIC_URL=ws://<LAN-IP>:7880`; restart.
   (Browsers allow camera/mic only on HTTPS or `localhost`; for a second device use the HTTPS setup below or
   Chrome's `--unsafely-treat-insecure-origin-as-secure` for testing.)
2. Register a host, verify from the e-mail, create an instant meeting with the waiting room on, start it.
3. Open the join link on a second device as a guest → host admits → both see and hear each other.
4. Host: mute guest, request unmute (guest must accept), share screen; with a recorder deployed, start/stop cloud recording
   (it appears in storage and in the Recording library); end meeting.
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
and the storage settings in the next section. `S3_PUBLIC_ENDPOINT` stays empty on AWS.

Mail uses the same real SMTP settings in development and production: `SMTP_HOST`, `SMTP_USERNAME`, `SMTP_PASSWORD` and
`APP_MAIL_FROM` are required, and Compose stops if one is missing. Development therefore sends real e-mail, so use real
addresses when testing. A personal Gmail account has a daily sending limit; use a mail service for production volume.

### File storage: local disk or AWS S3

`STORAGE_PROVIDER` chooses where everything is kept.

| Environment | Settings | Where data goes |
|---|---|---|
| Development | `STORAGE_PROVIDER=local`, `SPRING_PROFILES_ACTIVE=` (empty) | Shared files and recordings under `STORAGE_LOCAL_BASE_PATH` (`shared-files/`, `meeting-recordings/`), speaking clips in `RECORDING_LOCAL_PATH`. No AWS account, no MinIO. |
| Production | `STORAGE_PROVIDER=s3`, `SPRING_PROFILES_ACTIVE=prod`, `AWS_REGION`, `AWS_S3_BUCKET`, and an empty `S3_ENDPOINT=` line | One private bucket with the prefixes below. Access through the EC2 instance role. |

The `prod` profile always uses S3. The empty `S3_ENDPOINT=` line
matters: without it Compose points the API at MinIO. MinIO stays available as an option (`--profile minio`) and is not
needed in either row above.

Copying a development `.env` to production is the usual way to get this wrong. These values differ, and the checks below
stop startup rather than let a wrong one through:

| Setting | Development | Production on AWS | If wrong in production |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | empty | `prod` | |
| `STORAGE_PROVIDER` | `local` | `s3` | `local` stops startup |
| `RECORDING_STORAGE_TYPE` | `LOCAL` or empty | empty | `LOCAL` stops startup |
| `S3_ENDPOINT` | not set | present and empty | Without the line the API looks for MinIO and stops: no keys |
| `S3_PUBLIC_ENDPOINT` | empty (MinIO: `http://localhost:9000`) | empty | Any value stops startup |
| `AWS_S3_BUCKET` | empty | your bucket | Empty stops startup |
| `AWS_REGION` | empty | your region | Empty stops startup |
| `S3_FILES_BUCKET`, `S3_RECORDINGS_BUCKET` | empty (MinIO: `meet-files`, `meet-recordings`) | empty | A different bucket stops startup |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | empty (MinIO: its keys) | empty | Keep empty: the instance role is used |

There is no switch to keep clips on the server's disk in production. MinIO is for development: an S3-compatible server
under `prod` needs a `S3_PUBLIC_ENDPOINT` that browsers can reach, and speaking clips still go to AWS S3.

The database stores keys relative to the bucket, never a server name, so the same rows work after a move:

```text
s3://<bucket>/
  files/<meetingId>/<fileId>            shared files          never removed by age
  recordings/<meetingId>/<id>.mp4       whole-meeting video   APP_RECORDING_RETENTION_DAYS
  speaking-clips/DD-MM-YYYY/...         speaking clips        90 days (lifecycle rule + APP_SPEECH_CLIP_RETENTION_DAYS)
```

**Create the bucket yourself** (the API never creates or changes an AWS bucket): turn on *Block all public access* and
default encryption. Never add a public bucket policy; browsers only receive short-lived signed links
(`STORAGE_PRESIGN_TTL`), issued after the API has checked the caller.

**Instance role.** Attach an IAM role to the EC2 instance and keep `S3_ACCESS_KEY`, `S3_SECRET_KEY`,
`AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` empty. Because the API runs in a container, set the instance metadata
hop limit to 2, replacing `<instance-id>`:

```bash
aws ec2 modify-instance-metadata-options --instance-id <instance-id> --http-tokens required --http-put-response-hop-limit 2
```

Least-privilege policy, with `<bucket>` replaced:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"],
      "Resource": [
        "arn:aws:s3:::<bucket>/files/*",
        "arn:aws:s3:::<bucket>/recordings/*",
        "arn:aws:s3:::<bucket>/speaking-clips/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket", "s3:GetBucketLocation"],
      "Resource": "arn:aws:s3:::<bucket>"
    }
  ]
}
```

`s3:ListBucket` is what lets the API tell a missing object from a refused one. The role deliberately has no
`s3:PutLifecycleConfiguration`: set the rule below yourself.

**Lifecycle rule (speaking clips only).** Apply this once. Its filter limits it to `speaking-clips/`, so shared files
and whole-meeting recordings are never touched. Never add a rule without a prefix.

```bash
aws s3api put-bucket-lifecycle-configuration --bucket <bucket> --lifecycle-configuration '{
  "Rules": [{
    "ID": "expire-speaking-clips-90-days",
    "Status": "Enabled",
    "Filter": { "Prefix": "speaking-clips/" },
    "Expiration": { "Days": 90 },
    "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 7 }
  }]
}'
```

**Keeping the database and S3 in step.** A nightly job (03:45) deletes clips older than `APP_SPEECH_CLIP_RETENTION_DAYS`
from S3 and marks their rows deleted, keeping the row as history. The lifecycle rule is the safety net behind it. Keep
the two values equal. A clip that cannot be removed is passed over so the clips behind it still go, stays unchanged,
and is tried again the next night. One run stops after 20 minutes, and the rest wait for the next run.

To watch it, use the Prometheus counter `meet_speech_clips_retention_total`, with `result` set to `deleted`, `failed` or
`skipped`. Each run also logs a summary, and names the first 10 failed clips with the reason. A `failed` count that
keeps coming back means an object the role cannot delete, for example after `AWS_S3_PREFIX` changed.

If an S3 object has gone (expired, or removed by hand), playback and download answer "no longer available" and nothing
else breaks. Clip files on local disk are not checked in advance: a missing one is reported when it is played. Local
clips are never removed by age.

**Whole-meeting recordings** have their own retention, `APP_RECORDING_RETENTION_DAYS`, and are off by default
(`APP_CLOUD_RECORDING_ENABLED=false`): the control is hidden, manual starts are refused, automatic recording does not
start one, and speaking clips carry on as normal.

**Do not run Egress on the 4 GB application server.** It peaked at 4.19 CPU and 1.47 GB here, and it needs Chrome.
Run it on its own machine, assess capacity first, then set `APP_CLOUD_RECORDING_ENABLED=true` and start it
(`--profile egress`). With empty keys it uses that machine's IAM role; it needs `s3:PutObject` on
`recordings/*` plus the two multipart actions.

**Moving existing clips is a separate decision.** Clips already on a PC stay there. After switching to S3, those
rows answer "no longer available" until the files are copied:
`aws s3 sync <RECORDING_LOCAL_PATH> s3://<bucket>/speaking-clips --exclude "*.part"`.

### Speaking-clip storage (LOCAL / S3)
* `RECORDING_STORAGE_TYPE=LOCAL` (development, Windows): clips stay in `RECORDING_LOCAL_PATH`
  (`D:/MeetingApplication/Recordings`) as `DD-MM-YYYY/<Meeting>/<Participant>/DDMMYYHHmmss_<id>.webm`.
* `RECORDING_STORAGE_TYPE=S3` (production; empty means the same, and `prod` refuses `LOCAL`): `RECORDING_LOCAL_PATH` is a staging folder;
  each verified clip is uploaded to `s3://$AWS_S3_BUCKET/$AWS_S3_PREFIX/<same path>` before it is marked COMPLETED.
  An upload failure keeps it RECORDING and the recovery job retries every minute. Playback redirects to presigned URLs.
* Bucket, role and lifecycle rule: see *File storage* above.
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

- `DATA_ROOT` (`.env`, default `./docker`, git-ignored; e.g. `D:/meetly/docker` or `/srv/meetly`) holds `redis/`, `backups/`, and `minio/` with `--profile minio`. Shared files and recordings live in `STORAGE_LOCAL_BASE_PATH` (default `./storage`). Folders are created on first start. Prometheus and Grafana keep named volumes (their containers run as non-root users).
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
