# Meet Platform

Video meetings (LiveKit SFU), host controls, cloud & local recording, and Calendly-style scheduling.

| Path | Contents |
|---|---|
| `backend/` | Java 21 · Spring Boot 4 modular monolith (REST API, security, Flyway, LiveKit integration) |
| `web/` | React + TypeScript + Vite web client (see `web/README.md`) |
| `docs/` | Requirements, architecture, ERD, API contract, checklist, security, deployment, performance |
| `infra/` | Prometheus rules, Grafana provisioning |
| `loadtest/` | k6 (API) and LiveKit CLI (media) capacity tests |
| `docker-compose.yml` | Full local stack |

## Quick start
```bash
cp .env.example .env          # set secrets
docker compose up -d --build
```
Web app http://localhost:3000 · Mailpit http://localhost:8025 · API docs http://localhost:3000/swagger-ui.html

## Build and test without Docker
```bash
cd backend && ./mvnw test     # uses embedded PostgreSQL when Docker is absent, Testcontainers otherwise
cd web && npm ci && npm run typecheck && npm run lint && npm test && npm run build
```

## Status
See `docs/05-implementation-checklist.md` for what is done, what is tested, and known limitations.
Mobile (React Native) and desktop (Electron) clients are not started yet, and the 1,000-participant capacity
target has not been measured.
