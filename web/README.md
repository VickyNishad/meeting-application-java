# Meetly — web client

React + TypeScript single-page app for the video-meeting & scheduling platform. It talks to the Spring Boot
API described in [`../docs/04-api-contracts.md`](../docs/04-api-contracts.md) and to LiveKit for real-time media.

**Stack:** React 19, TypeScript (strict), Vite 7, Tailwind CSS 4, shadcn/ui-style components (Radix primitives +
class-variance-authority), React Router 7, TanStack Query 5, React Hook Form + Zod, LiveKit
(`livekit-client`, `@livekit/components-react`, `@livekit/components-styles`), lucide-react, sonner.
Tests: Vitest + React Testing Library, Playwright.

## Setup

```bash
cd web
npm install
npm run dev          # http://localhost:5173 — /api is proxied to http://localhost:8080
```

The dev server proxies `/api`, `/oauth2/` and `/login/oauth2/` to the backend (the OAuth paths keep the browser
`Host` and add `X-Forwarded-*` so Spring builds redirect URIs against the dev origin). The backend must be running for
anything beyond the sign-in screen. All data comes from the API; there is no
mock data. When an endpoint fails, the UI shows the Problem Details `detail` (and maps `errors[]` onto form fields).

## Scripts

| Script | What it does |
|---|---|
| `npm run dev` | Vite dev server with `/api` proxy |
| `npm run build` | Type-check + production build into `dist/` |
| `npm run preview` | Serve the production build locally |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run lint` | ESLint (flat config), zero warnings allowed |
| `npm run test` | Vitest unit/component tests (`vitest run`) |
| `npm run e2e` | Playwright tests (starts the dev server; run `npx playwright install chromium` once) |

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | `/api/v1` | Base URL of the REST API (build-time). Keep the default when the SPA and API share an origin (dev proxy / nginx). |
| `VITE_DEV_PROXY_TARGET` | `http://localhost:8080` | Dev server only: where `/api` is proxied. |
| `VITE_PUBLIC_APP_URL` | `window.location.origin` | Origin used for shareable join/booking links. |
| `E2E_BASE_URL` | — | Playwright: test an already-running deployment instead of starting Vite. |
| `E2E_USER_EMAIL`, `E2E_USER_PASSWORD` | — | Playwright: verified account for the backend flow (skipped when unset or when the API is down). |

Copy `.env.example` to `.env.local` to override.

## Auth model

* The access token (15 min JWT) is kept **in memory only** — never in `localStorage`/`sessionStorage`.
* On load, and on any `401` from a Bearer endpoint, the client calls `POST /api/v1/auth/refresh` once
  (`credentials: 'include'`, `X-Requested-With: XMLHttpRequest`, HttpOnly `mp_refresh` cookie) and retries.
  Concurrent `401`s share a single refresh. If refresh fails the user is sent to `/login?next=…`.
* Meeting participants (including guests) receive a participant secret (`waitToken`) from `POST /join`; it is sent as
  `X-Participant-Token` on join-status / leave / chat / files / local-recording calls and kept in `sessionStorage`
  (keyed by meeting) so a page refresh in the room can obtain a fresh LiveKit token via `GET join-status`.

## Single sign-on & calendar integrations

* **OAuth2 login:** the login page lists `GET /auth/oauth2/providers` as "Continue with Google/Microsoft" buttons
  (nothing is shown when the list is empty). They do a full-page navigation to the relative `authorizationUrl`
  (`/oauth2/…`, proxied). After the provider round-trip the backend sets the refresh cookie and redirects to
  `/oauth/callback`, which refreshes the session, loads `/users/me` and goes to the dashboard.
  `/login?error=oauth|oauth_account|oauth_profile` shows a friendly explanation.
* **Calendars:** *Settings → Calendar integrations* lists `GET /calendar/connections`. "Connect" calls
  `POST /calendar/connections/{provider}/authorize` and navigates to the returned URL (disabled with
  "Not configured on server" when `available=false`); "Disconnect" calls `DELETE`. The backend returns to
  `/settings?calendar=connected|error`, which is shown as a toast.

## Pages

| Route | Page |
|---|---|
| `/oauth/callback` | OAuth2 login landing (refresh → profile → dashboard) |
| `/login`, `/register`, `/verify-email?token=`, `/forgot-password`, `/reset-password?token=` | Authentication |
| `/dashboard` | Live + upcoming meetings, instant "New meeting", join-by-code, personal room |
| `/meetings` (`?tab=past`) | Upcoming / past meetings |
| `/meetings/new`, `/meetings/:id/edit` | Create / edit meeting (all `CreateMeetingRequest` fields, recurrence, time zone) |
| `/meetings/:id` | Details, invitations, copy link, cancel, start/join, attendance, recordings |
| `/j/:joinCode` | Join by link: device test, guest name, password, waiting room |
| `/meetings/:id/room` | Live meeting room (lazy-loaded chunk incl. LiveKit) |
| `/recordings` | Recording library: play (signed URL), download, delete |
| `/scheduling/availability` | Weekly hours, date overrides, time zone |
| `/scheduling/pages` | Booking pages CRUD |
| `/scheduling/bookings` | Host bookings (upcoming/past, cancel) |
| `/book/:slug`, `/book/:slug/confirmed` | Public booking page and confirmation (manage link) |
| `/bookings/manage/:token` | Invitee cancel / reschedule |
| `/settings` | Profile, calendar integrations, password, sessions, sign out everywhere |
| `/organizations` | Create organization, settings, members & roles |
| `/admin` | `SYSTEM_ADMIN` only: stats, users (enable/disable, role), live meetings, audit log |

## Meeting room features

Gallery and active-speaker layouts, camera/mic/screen-share toggles with device switching, full screen,
connection quality + reconnecting banner, participant list, chat (history via REST, live via the `chat` data topic)
and file sharing, raise hand and emoji reactions (`hand` / `reactions` topics), host controls (waiting room
admit/deny/admit-all polled every 3 s, mute / mute-all / request-unmute, remove, co-host, lock, screen-share policy,
waiting room and chat toggles, cloud recording start/stop), recording indicator driven by the `recording` topic,
consent dialog for `request-unmute` (the client never unmutes without a click), handling of `removed` /
`meeting-ended`, and a leave vs. end-for-everyone dialog.

## Browser support

* **Meetings:** current Chrome, Edge, Firefox and Safari (desktop and mobile) — WebRTC via LiveKit.
  Camera/microphone require HTTPS (or `localhost`).
* **Screen sharing:** desktop Chrome/Edge/Firefox/Safari. Mobile browsers generally cannot share the screen
  (`getDisplayMedia` is unavailable); the button reports the browser error. Sharing tab/system audio is Chromium-only.
* **Local recording** (record button in the room): needs `MediaRecorder` + `getDisplayMedia` on a secure origin, which
  means desktop Chrome/Edge (best, WebM VP9/Opus, `preferCurrentTab` to capture the meeting tab), Firefox (WebM, user
  picks a window/screen) and Safari 14.1+ (MP4). It is disabled with an explanation on unsupported browsers (most mobile).
  - Meeting audio (your mic + every remote participant) is mixed in with WebAudio, so the recording has the
    conversation even when the browser cannot capture tab/system audio. **System/tab audio capture is
    browser-dependent** (Chromium only) and is never promised.
  - The server is notified (`POST /meetings/{id}/local-recordings` STARTED/STOPPED) *before* recording begins; if that
    call fails the recording is not started, so other participants always see the indicator.
  - The file is held in memory and downloaded on stop (also when the user ends the capture from the browser UI or
    leaves the room). Recordings auto-stop at 2 GB to avoid running out of memory. Permission denial, capture
    end, recorder errors and export failures are surfaced as toasts.

## Docker

```bash
docker build -t meetly-web ./web
docker run -p 8081:80 --network <compose-network> meetly-web
```

Multi-stage build: `node:24-alpine` builds `dist/`, `nginx:alpine` serves it with SPA fallback, long-term caching of
hashed assets, security headers, and proxies `/api/`, `/oauth2/` and `/login/oauth2/` to `backend:8080` (see `nginx.conf`). `/healthz` is the container
health check.

## Project layout

```
src/
  App.tsx              routes (React.lazy per page), providers
  components/          shared UI (ui/ = shadcn-style primitives, layout/, SlotPicker, meetings helpers)
  lib/
    api/               fetch client (refresh logic), Problem Details, typed endpoints, DTOs
    auth/              AuthProvider, route guards
    recorder/          local recording (feature detection, MediaRecorder + WebAudio mix)
    time.ts            IANA zone helpers, slot grouping
  pages/               auth/, meetings/, room/, recordings/, scheduling/, public/, settings/, admin/
e2e/                   Playwright specs
```
