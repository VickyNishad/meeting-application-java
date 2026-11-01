# 02 — Architecture

## System context

```mermaid
flowchart LR
    subgraph Clients
        WEB[Web app<br/>React + LiveKit JS]
        MOB[Mobile app<br/>React Native - planned]
        DESK[Desktop app<br/>Electron - planned]
    end

    subgraph Edge
        RP[Reverse proxy / TLS<br/>nginx or Caddy]
    end

    subgraph Control["Control plane (stateless, horizontally scaled)"]
        API[Spring Boot API<br/>modular monolith]
    end

    subgraph Media["Media plane (scaled independently)"]
        LK[LiveKit SFU]
        EG[LiveKit Egress<br/>recording workers]
        TURN[TURN/TLS<br/>built into LiveKit]
    end

    subgraph Data
        PG[(PostgreSQL)]
        RD[(Redis)]
        S3[(S3-compatible<br/>object storage)]
    end

    SMTP[SMTP]
    CAL[Google / Microsoft<br/>calendar APIs]

    WEB & MOB & DESK -- HTTPS REST --> RP --> API
    WEB & MOB & DESK -- WebRTC UDP / TCP / TURN-TLS --> LK
    API -- server API: rooms, mute, remove, data, egress --> LK
    LK -- signed webhooks --> API
    LK <--> RD
    EG <--> RD
    EG -- composite capture --> LK
    EG -- MP4 / OGG upload --> S3
    API --> PG
    API --> RD
    API -- presigned URLs, file uploads --> S3
    API --> SMTP
    API --> CAL
```

Key decisions:

* **Media never touches the API.** Browsers exchange media with the SFU; the API issues ≤ 10-minute join tokens
  and calls the SFU's admin API for moderation.
* **The SFU is the source of truth for live presence.** Durable state (who joined, when, recordings) is updated from
  signature-verified, idempotent webhooks.
* **Modular monolith.** One deployable API with strict module boundaries (below). No Kafka or microservices:
  current load and durability needs are met by PostgreSQL (outbox for e-mail, `SKIP LOCKED` dispatch) and Redis
  (rate limits). The LiveKit SFU and Egress workers are separately deployable and scale independently.

## Backend modules

```mermaid
flowchart TB
    common[common<br/>config, security, exceptions, audit,<br/>storage, idempotency, observability]
    identity --> common
    organization --> identity
    media[media<br/>port + LiveKit adapter + webhooks] --> common
    meeting --> identity & organization
    room --> meeting & media
    participant --> meeting & room & media
    moderation --> participant
    recording --> moderation
    chat --> participant
    file --> participant
    scheduling --> identity
    booking --> scheduling & meeting
    calendar --> booking
    notification --> identity & meeting & booking & calendar
    administration --> identity & meeting & recording & booking
```

Dependency-inversion points that keep the graph acyclic:

| Interface | Declared in | Implemented in |
|---|---|---|
| `SessionStatusChecker` | common.security | identity (`SessionService`) |
| `MeetingParticipationLookup` | meeting | participant (`ParticipationLookupService`) |
| `MediaService` | media.service | media.adapter.livekit (`LiveKitMediaAdapter`) |
| `ObjectStorage` | common.storage | `MinioObjectStorage` (any S3 API) |
| `CalendarProvider` | calendar.adapter | Google, Microsoft |

Cross-module reactions use Spring application events (`RoomEvents.SessionEnded`, `MediaWebhookEvent`,
`BookingEvents.*`, `IdentityEvents.*`, `MeetingEvents.*`). Listeners run in the publisher's transaction, so a
side effect (e.g. queued e-mail) is committed only with the change that caused it. External calls that must
not hold a database transaction (calendar sync, SFU room deletion, data messages) run after commit.

## Key flows

### Join and admission

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant A as API
    participant DB as PostgreSQL
    participant L as LiveKit
    C->>A: POST /meetings/{id}/join (bearer or guest name, password)
    A->>DB: SELECT meeting FOR UPDATE (serializes joins)
    A->>A: status, privacy, guest policy, password, removed?, lock, capacity
    alt no live session and caller may start it
        A->>L: CreateRoom(name, maxParticipants)
        A->>DB: insert meeting_rooms (ACTIVE), meeting LIVE
    end
    alt waiting room applies
        A->>DB: participant WAITING
        A-->>C: WAITING + participantId + secret
        A--)L: data "waiting-room" to moderators (after commit)
        loop every 2 s
            C->>A: GET join-status (secret)
        end
    else admitted
        A->>DB: participant ADMITTED
        A-->>C: ADMITTED + LiveKit token (10 min) + wss URL
    end
    C->>L: WebRTC connect with token
    L--)A: webhook participant_joined (signed)
    A->>DB: claim event id (idempotent), participant JOINED
```

### Cloud recording

```mermaid
sequenceDiagram
    participant H as Host client
    participant A as API
    participant L as LiveKit
    participant E as Egress
    participant S as Object storage
    H->>A: POST /meetings/{id}/recordings
    A->>A: moderator + RECORD permission, no active recording
    A->>A: insert recording STARTING (unique per session)
    A--)L: data "recording STARTED" to all (indicator first)
    A->>L: StartRoomCompositeEgress(filepath)
    L->>E: assign job
    E->>S: upload MP4 (credentials only in egress config)
    L--)A: egress_updated / egress_ended webhooks
    A->>A: ACTIVE → AVAILABLE (size, duration, retention) or FAILED
    H->>A: POST /recordings/{id}/playback-url
    A-->>H: presigned GET URL (15 min), access logged
```

### Booking without double booking

1. Slot list = weekly rules in host zone (DST-aware) − exceptions − confirmed bookings (with buffers)
   − host's scheduled meetings − notice/advance window.
2. `POST /bookings` re-validates that the requested start is still an offered slot.
3. The insert is flushed immediately; PostgreSQL's exclusion constraint
   `EXCLUDE USING gist (host_id WITH =, tstzrange(blocked_start, blocked_end) WITH &&) WHERE status='CONFIRMED'`
   lets exactly one of several concurrent transactions commit. The others surface as `409 SLOT_UNAVAILABLE`.

## Scaling model

| Component | State | Scale by |
|---|---|---|
| API | Stateless (JWT + DB + Redis) | Replicas behind the proxy; HikariCP pool bounded (`DB_POOL_SIZE`) per replica |
| LiveKit SFU | Room state in memory, cluster coordination in Redis | Add nodes; LiveKit places rooms; one room is hosted on one node |
| Egress | Stateless workers | Add workers; each composite recording uses ~2–4 vCPU (headless Chrome + encoder) |
| PostgreSQL | Primary | Vertical first; read replicas for reporting if ever needed |
| Redis | Rate-limit counters, LiveKit coordination | Single instance with AOF; Sentinel/managed Redis in production |

## Technology choices and deviations

| Area | Choice | Note |
|---|---|---|
| Backend | Java 21, Spring Boot 4.0.9, Spring Security 7, Hibernate 7, Flyway 11 | Latest stable line at build time |
| Media | LiveKit SFU v1.13, Egress v1.15, `io.livekit:livekit-server` 0.16 SDK | |
| Object storage | S3 API (MinIO in compose) | Upstream MinIO no longer publishes images; compose pins a maintained build |
| Web | React, TypeScript, Vite, Tailwind, shadcn/ui-style components, TanStack Query, RHF + Zod, LiveKit React | |
| Desktop | **Electron** (recommended) | Evaluation: Electron ships Chromium, so `getDisplayMedia`/`desktopCapturer`, WebRTC and MediaRecorder behave the same as in Chrome on all OSes; Tauri uses WebView2/WKWebView/WebKitGTK whose WebRTC/screen-capture support differs per OS (notably WebKitGTK on Linux). Not implemented yet (Phase 6). |
| Mobile | React Native + `@livekit/react-native` | Not implemented yet (Phase 6). |
