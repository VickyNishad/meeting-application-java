# 10 — Running Meetly on one Windows PC without Docker

Everything lives in `%USERPROFILE%\.meet-tools` (portable binaries, data and logs).

## Start / stop
```powershell
cd C:\Users\E15071\Desktop\Meeting
powershell -ExecutionPolicy Bypass -File .\start-local.ps1     # starts what is not running, opens the browser
```
Stop: close the minimized LiveKit / API / web windows; PostgreSQL:
`& "$env:USERPROFILE\.meet-tools\pg\bin\pg_ctl.exe" -D "$env:USERPROFILE\.meet-tools\pgdata" stop`

| Service | Address | Logs |
|---|---|---|
| Web app | http://127.0.0.1:5173 | `web.log` |
| API | http://127.0.0.1:8081 | `api.log` |
| Swagger UI | http://127.0.0.1:8081/swagger-ui.html (also http://127.0.0.1:5173/swagger-ui/index.html) | |
| OpenAPI JSON | http://127.0.0.1:8081/v3/api-docs | |
| LiveKit (video) | ws://127.0.0.1:7880, media UDP 50000-50100 / TCP 7881 | `livekit.log` |
| PostgreSQL 14 | 127.0.0.1:5432 | `pg.log` |

## PostgreSQL
| | |
|---|---|
| Binaries | `%USERPROFILE%\.meet-tools\pg\bin` (portable PostgreSQL 14.22) |
| Data directory | `%USERPROFILE%\.meet-tools\pgdata` |
| Host / port | `127.0.0.1` / `5432` (local connections only) |
| Database | `meet` |
| User / password | `meet` / `localdev-db-password` (local development only) |
| JDBC URL | `jdbc:postgresql://127.0.0.1:5432/meet` |

The schema (all tables, constraints, seed roles) is created automatically by Flyway when the API starts
(`backend/src/main/resources/db/migration`). To browse the data, connect pgAdmin, DBeaver or the IntelliJ
database tool with the values above.

Backup: `pg_dump` is not part of the portable build; use pgAdmin/DBeaver "Backup", or stop PostgreSQL and copy
the `pgdata` folder.

## Using Swagger
OpenAPI / Swagger UI is off by default. Enable it for local development by adding `APP_OPENAPI_ENABLED = 'true'`
to the variables in `%USERPROFILE%\.meet-tools\start-api.ps1` and restarting the API.

1. Open http://127.0.0.1:8081/swagger-ui.html
2. `Authentication → POST /api/v1/auth/login` → *Try it out* → body
   `{"email":"…","password":"…","clientType":"NATIVE"}` → copy `accessToken`.
3. Click **Authorize**, paste the token. Every protected endpoint can now be called from the page.

## Video connection ("Reconnecting" forever)
Desktop Chrome/Edge never use `127.0.0.1` for WebRTC media, so LiveKit must advertise the PC's network address:
`rtc.node_ip` in `%USERPROFILE%\.meet-tools\livekit-local.yaml` (currently `192.168.207.244`).
If the PC's IP changes (`ipconfig`), update `node_ip` and restart LiveKit. A VPN that blocks local UDP can also
cause this; LiveKit falls back to TCP 7881.

## Limits of this setup
No Redis (rate limiting off), no S3 (file sharing and recording playback return 503), no egress worker (cloud
recording returns 503), no SMTP (e-mail verification disabled). Use `docker compose` for the full stack.
