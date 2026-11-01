# 08 — Performance and Capacity Testing

**Status: the capacity target has NOT been measured yet.** The build machine had no Docker and no media
infrastructure. The kit below is ready; results must be recorded in the table at the end before release.

## Target scenario (from the requirements)
* 100 concurrent rooms × 10 participants = 1,000 participants.
* 3 video publishers per room (720p simulcast), all participants publish audio and subscribe.
* 10 rooms with an active screen share; 10 rooms with cloud recording.
* 30 minutes steady state, then recovery tests.

## Release targets
| Metric | Target |
|---|---|
| API join p95 / p99 | < 500 ms / < 1.5 s |
| API read endpoints p95 | < 300 ms |
| API error rate | < 1 % |
| Media packet loss p95 | < 2 % |
| Media jitter p95 | < 30 ms |
| Time from join to first remote frame | < 3 s |
| Recording success rate | 100 % of started recordings AVAILABLE |
| Recovery: kill one API replica | no media session dropped; API errors < 5 s |
| Recovery: restart Redis | rooms continue; rate limiter fails open |

## How to run
1. Provision production-like infrastructure (see `07-deployment.md`): 2 API replicas, 1–2 LiveKit nodes
   (≥ 16 vCPU each), 2–3 Egress workers, managed PostgreSQL.
2. For the test environment only, raise or disable the per-IP rate limits (`APP_RATE_LIMIT_ENABLED=false`):
   all synthetic guests come from a few load-generator IPs.
3. Control plane (k6): `k6 run -e BASE_URL=https://… -e USERS_FILE=users.json loadtest/api-join.js`
4. Media plane (LiveKit CLI, from separate load-generator machines):
   `ROOMS=100 PER_ROOM=10 DURATION=30m sh loadtest/run-media-load.sh`
5. Start screen shares / recordings in a sample of rooms from the web UI or via the API.
6. Watch Grafana: API latency (`http_server_requests_seconds`), Hikari pool, LiveKit `livekit_*` metrics
   (packet loss, NACKs, CPU), egress health.

## Optimizations already in place
Stateless API, bounded Hikari pool, virtual threads, indexed hot queries, row locks only per meeting,
asynchronous e-mail outbox, egress and SFU scaled separately, server-side data messages only (no polling of
media), clients subscribe through LiveKit adaptive stream/dynacast (web client).

## Results
| Date | Environment | Scenario | Join p95 | Error rate | Packet loss p95 | CPU (SFU) | Pass? |
|---|---|---|---|---|---|---|---|
| — | — | not yet run | — | — | — | — | — |
