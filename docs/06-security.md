# 06 — Security

| Control | Implementation |
|---|---|
| Transport | HTTPS/WSS terminated at the reverse proxy; HSTS; LiveKit TURN over TLS (443/5349) for restrictive networks |
| Authentication | Access JWT (HS256, 15 min, issuer + audience checked) in `Authorization` header; opaque refresh token (256-bit) rotated on each use, stored as SHA-256. Social sign-in trusts only provider-verified `email` claims (never `preferred_username`); Microsoft ID-token issuers are validated against the token tenant (`tid`) |
| Session revocation | Each access token carries its session id; the decoder rejects revoked sessions (≤ 10 s cache). Logout, logout-all, password change/reset, user disable and role change revoke sessions |
| Refresh-token theft | Reuse of a rotated token outside a 30 s race window revokes the whole session and is audited |
| CSRF | API is bearer-authenticated (not cookie-authenticated). The refresh cookie (`mp_refresh`) is HttpOnly, Secure, `SameSite=Strict`, path `/api/v1/auth`, and refresh/logout additionally require `X-Requested-With`. The calendar OAuth `state` is HMAC-signed, bound to the initiating browser by the HttpOnly `SameSite=Lax` cookie `mp_calendar_oauth` (path = callback) and usable once |
| CORS | Explicit origin allow-list (`APP_CORS_ALLOWED_ORIGINS`), credentials only for listed origins |
| Passwords | bcrypt via `DelegatingPasswordEncoder` (algorithm upgradeable); 12–128 chars, common/repetitive passwords and e-mail name rejected; after 10 failures the failing client IP is locked out of that account for 15 min (per account + IP, so strangers cannot lock the owner out) |
| Authorization | Resource-level checks in services: host/co-host/participant/org-admin/system-admin resolved from the database. Outsiders receive 404 for private resources (no existence probing). Lasting access from participation requires having been admitted (waiting, denied or removed participants get none); `CO_HOST` is scoped to one live session. Role → permission matrix lives in `role_permissions` |
| Media authorization | LiveKit tokens: one room, explicit `canPublishSources`, no admin/record grants, 10-min TTL; identity = participant id. Removed participants connecting with a still-valid token are ejected on the `participant_joined` webhook, and participants who left are ejected on reconnect if the room was locked or filled meanwhile |
| Consent | No server-side unmute exists; unmute is a request the participant must accept. Recording indicators are delivered synchronously before capture starts; cloud recording does not start and the local-recording notice returns `503 MEDIA_PROVIDER_UNAVAILABLE` (client aborts the local recording) if the room cannot be notified |
| Webhooks | LiveKit JWT signature + SHA-256 body hash verified on the raw body; idempotent by event id |
| Recordings and files | Private buckets; access only via 15-minute presigned URLs after authorization; each recording access logged; storage credentials for egress live only in the egress worker config |
| Uploads | 25 MB limit, content-type + extension allow-list + file-signature (magic bytes) check for PDF/images/Office/ZIP, sanitized file names, server-generated object keys, SHA-256 stored |
| Rate limiting | Redis fixed windows (hashed keys): login (IP, e-mail+IP, e-mail), registration, invitation e-mails (500/host/h) and reschedule notifications (20/host/h), password reset, join, meeting password attempts, slot queries, bookings, chat, uploads. Fails open if Redis is down |
| Input validation | Bean Validation on all DTOs; IANA zones and RRULEs validated; paging clamped to 100 |
| Secrets | Only via environment variables (`.env` is git-ignored, `.env.example` has placeholders); JWT secret ≥ 32 bytes enforced at startup; calendar OAuth tokens encrypted with AES-256-GCM |
| Logging | Never logs passwords, tokens, signed URLs or provider secrets; the JDBC driver runs with `logServerErrorDetail=false` so failed statements never echo bound values (invitee names/e-mails) or constraint key values; outbox bodies with one-time links are redacted after delivery; error responses never include stack traces |
| Audit | Append-only `audit_logs` with actor, resource, IP, trace id for auth events, moderation, recordings, bookings and admin changes |
| Meeting-link abuse | 10-character random join codes (≈ 49 bits) + by-code lookup rate limit + optional password, waiting room, lock and guest policy |
| Dependencies | CI: `npm audit`, Trivy filesystem scan (Maven + npm), dependency review on PRs, gitleaks secret scan |
| Headers | `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer` on the API; the web nginx config sets the SPA's CSP |

## Operational checklist before go-live
- [ ] Rotate every secret in `.env`; store them in a secret manager (Vault / cloud KMS / Kubernetes secrets).
- [ ] `SPRING_PROFILES_ACTIVE=prod` (forces `APP_COOKIE_SECURE=true`, `APP_OPENAPI_ENABLED=false`, `APP_REQUIRE_EMAIL_VERIFICATION=true`).
- [ ] Set `APP_METRICS_SCRAPE_PASSWORD` (Prometheus scrapes with HTTP Basic `prometheus:<password>`). The web nginx serves only `/actuator/health*` and returns 404 for every other `/actuator/` path.
- [ ] The API (8080) and MinIO (9000/9001) are not published beyond loopback; all browser traffic enters through nginx/Caddy.
- [ ] Never commit runtime data: `docker/backups`, `docker/redis`, `docker/minio`, `docker/Recordings` are git-ignored (a database dump holds user e-mails and password hashes).
- [ ] Restrict LiveKit's HTTP API (7880) to the API servers, except the WebSocket signalling path for clients.
- [ ] Enable encryption at rest for PostgreSQL volumes and the object store; bucket policies private.
- [ ] Recording consent wording reviewed by legal for each jurisdiction served, including the automatic-recording notice on the join page ("By joining you agree to be recorded").
