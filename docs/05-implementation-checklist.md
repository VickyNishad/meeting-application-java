# 05 — Implementation Checklist

Status legend: ✅ implemented and covered by automated tests · 🟢 implemented, compiles/builds, not yet exercised
against real infrastructure · 🟡 partial · ⬜ not started. "Real infrastructure" means a running LiveKit SFU,
Egress, S3 storage, SMTP and two real browsers.

Last verified: 2026-11-02. Backend: `./mvnw verify` → 163 tests in 33 classes, 0 failures, 1 skipped (real
PostgreSQL; CI uses PostgreSQL 17 via Testcontainers). Web (in the `node:24` build image): typecheck, lint,
186 unit tests in 28 files, production build. Playwright suites not re-run on this date.

## Phase 1 — Analysis and design
| Item | Status |
|---|---|
| Requirements and acceptance criteria (`01`) | ✅ |
| Architecture diagrams (`02`) | ✅ |
| ERD (`03`) | ✅ |
| API contracts (`04`) + generated OpenAPI (`/v3/api-docs`) | ✅ |
| Desktop framework evaluation (Electron chosen, see `02`) | ✅ |

## Phase 2 — Foundation
| Item | Status | Evidence |
|---|---|---|
| Spring Boot 4 modular monolith, Flyway V1/V2, entity/schema validation | ✅ | context starts with `ddl-auto=validate` in every integration test |
| Registration, e-mail verification, login, refresh rotation + reuse detection, logout, logout-all | ✅ | `AuthFlowIntegrationTest` |
| Password reset, change password, session list/revoke, lockout, rate limiting (Redis) | 🟢 | rate limiter disabled in tests (no Redis) |
| OAuth2 sign-in (Google/Microsoft) | 🟢 | profile `oauth`; needs provider credentials |
| Organizations and membership roles | 🟢 | |
| RFC 9457 errors with codes, OpenAPI, audit log, idempotency keys | ✅ | asserted across integration tests |
| CI (GitHub Actions: build, tests, npm audit, Trivy, gitleaks, image builds) | 🟢 | workflow written, not yet run on GitHub |

## Phase 3 — Meetings and real-time media (vertical slice)
| Item | Status | Evidence |
|---|---|---|
| Create instant/scheduled/1:1/recurring meetings, invitations with ICS, personal room | ✅ / 🟢 | lifecycle test; personal room 🟢 |
| Secure join: privacy, guests, password, lock, capacity, waiting room, join-before-host | ✅ | `MeetingLifecycleIntegrationTest` |
| LiveKit room creation, ≤10-min scoped tokens | ✅ | token grants verified with the real SDK in `LiveKitMediaAdapterTest`; SFU calls verified on a mock |
| Signed, idempotent webhooks → presence (JOINED/LEFT), room finished | ✅ | signature verified with real SDK; duplicate delivery test |
| Web room: real audio/video, devices, gallery/speaker, screen share, reconnect UI | 🟢 | built against `livekit-client`; **not yet run between two real clients** |
| End meeting, persisted history and participant durations | ✅ | |

## Phase 4 — Host controls and recording
| Item | Status |
|---|---|
| Mute / mute all / request-unmute (no server unmute) / remove / admit / deny / lock / co-host / screen-share policy / chat toggle / audit trail | ✅ (API) · 🟢 (UI) |
| Host leaves → automatic co-host promotion | 🟢 |
| Cloud recording via Egress, lifecycle from webhooks, failures, retention job, signed playback/download, access log | ✅ (API, egress mocked) |
| Local recording (MediaRecorder + WebAudio mix, room notification before start) | 🟢 |
| Chat (persist + server relay) and file sharing (allow-list, 25 MB, signed URLs) | 🟢 |

## Phase 5 — Scheduling
| Item | Status |
|---|---|
| Availability rules/exceptions, DST-correct slot calculation, viewer time zones | ✅ `SlotCalculatorTest` (spring-forward, fall-back, cross-zone, buffers, notice, exceptions) |
| Booking pages, public booking, confirmation, reschedule, cancel via manage token | ✅ |
| Concurrency-safe double-booking protection | ✅ 12 concurrent requests → exactly 1 booking; raw-SQL overlap rejected by the constraint |
| E-mail outbox with retry and SKIP LOCKED dispatch, reminders (24 h / 1 h), ICS | ✅ (queueing) · 🟢 (SMTP delivery) |
| Google / Microsoft calendar connect and event sync | 🟢 needs OAuth app credentials |

## Phase 6 — Mobile and desktop
| Item | Status |
|---|---|
| React Native app (LiveKit RN SDK, permissions, push) | ⬜ |
| Electron desktop app (screen picker via `desktopCapturer`, local recording) | ⬜ |

## Phase 7 — Hardening and operations
| Item | Status |
|---|---|
| Admin: stats, users enable/disable/role, live meetings, audit search | ✅ (access control) · 🟢 |
| Metrics (Micrometer/Prometheus), tracing (OpenTelemetry → Jaeger), alert rules | 🟢 |
| Docker images, Compose stack (Postgres, Redis, S3, Mailpit, LiveKit, Egress, API, web, observability) | 🟢 run locally on Docker Desktop; `docker compose config` valid for all profiles |
| Production hardening of the Compose stack: `prod` profile by default, API/MinIO on loopback, LiveKit UDP range matches published ports, embedded TURN (opt-in), Caddy HTTPS (`--profile tls`), CSP on the SPA, `/actuator/*` except health blocked at nginx | 🟢 configs validated (nginx -t, caddy validate, LiveKit boots with TURN); not yet run on a public server |
| Load test kit (k6 control plane, `lk load-test` media plane) | 🟢 scripts only — **capacity target not yet measured** |
| Production deployment guide, backup/restore, firewall/TURN | ✅ docs |

## Next tasks (in order)
1. Run `docker compose up` and complete the manual two-device test in `07-deployment.md` §"Smoke test".
2. Run the capacity test (`08-performance-testing.md`) on production-like hardware; record results there.
3. Phase 6: React Native and Electron clients.
4. Browser E2E against the compose stack (Playwright with two contexts and fake media devices).
5. Push-notification provider for mobile; optional transcription/AI module.
