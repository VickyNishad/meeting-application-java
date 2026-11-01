# 03 — Database Design (PostgreSQL)

Schema source of truth: `backend/src/main/resources/db/migration` (Flyway). Hibernate runs with
`ddl-auto=validate`, so the application refuses to start if entities and schema diverge.

Conventions: UUID primary keys assigned by the application; `timestamptz` for every instant (stored in UTC);
IANA zone ids stored next to local dates/times; `version` columns for optimistic locking; secrets (refresh tokens,
verification tokens, manage tokens, participant secrets) stored only as SHA-256 hashes; passwords as bcrypt.

```mermaid
erDiagram
    users ||--o| user_credentials : "has"
    users ||--o{ external_identity_links : "signs in with"
    users ||--o{ user_sessions : "owns"
    users ||--o{ verification_tokens : "receives"
    users ||--o{ organization_members : "belongs"
    organizations ||--o{ organization_members : "has"
    roles ||--o{ role_permissions : ""
    permissions ||--o{ role_permissions : ""
    roles ||--o{ organization_members : "role"
    roles ||--o{ meeting_participants : "role"

    users ||--o{ meetings : "hosts"
    organizations ||--o{ meetings : "owns"
    meetings ||--o{ meeting_invitations : "invites"
    meetings ||--o{ meeting_rooms : "live sessions"
    meetings ||--o{ meeting_participants : "attendance"
    meeting_rooms ||--o{ meeting_participants : "in session"
    meetings ||--o{ recordings : "has"
    meeting_rooms ||--o{ recordings : "captured in"
    recordings ||--o{ recording_access_logs : "accessed"
    meetings ||--o{ chat_messages : "has"
    meeting_participants ||--o{ chat_messages : "sent"
    meetings ||--o{ file_metadata : "has"

    users ||--o| availability_schedules : "zone"
    users ||--o{ availability_rules : "weekly"
    users ||--o{ availability_exceptions : "dated"
    users ||--o{ booking_pages : "owns"
    booking_pages ||--o{ bookings : "receives"
    bookings |o--o| meetings : "creates"
    bookings |o--o| bookings : "rescheduled from"
    users ||--o{ calendar_connections : "connects"

    users ||--o{ notifications : "receives"
    users ||--o{ audit_logs : "actor"

    users {
        uuid id PK
        varchar email "unique on lower(email)"
        varchar display_name
        varchar time_zone "IANA"
        bool email_verified
        varchar system_role "SYSTEM_ADMIN | USER"
        varchar status "ACTIVE | DISABLED"
        bigint version
    }
    user_sessions {
        uuid id PK
        uuid user_id FK
        varchar refresh_token_hash "unique"
        varchar previous_token_hash "reuse detection"
        timestamptz expires_at
        timestamptz revoked_at
    }
    meetings {
        uuid id PK
        varchar join_code "unique"
        varchar type "INSTANT | SCHEDULED | PERSONAL_ROOM | ONE_TO_ONE"
        varchar status "SCHEDULED | LIVE | ENDED | CANCELLED"
        varchar privacy "LINK | ORGANIZATION | INVITEES_ONLY"
        uuid host_id FK
        uuid organization_id FK
        timestamptz start_time
        timestamptz end_time
        varchar time_zone
        varchar recurrence_rule "RFC 5545"
        varchar password_hash
        int max_participants "2..300"
        bool locked
    }
    meeting_rooms {
        uuid id PK
        uuid meeting_id FK "unique where ACTIVE"
        varchar livekit_room_name "unique"
        varchar status "ACTIVE | ENDED"
        int peak_participants
    }
    meeting_participants {
        uuid id PK "= LiveKit identity"
        uuid meeting_id FK
        uuid room_id FK
        uuid user_id FK "null for guests"
        varchar role
        varchar state "WAITING | ADMITTED | JOINED | LEFT | REMOVED | DENIED"
        varchar wait_token_hash
    }
    recordings {
        uuid id PK
        uuid meeting_id FK
        uuid room_id FK "unique while in flight"
        varchar egress_id "unique"
        varchar status
        varchar storage_key
        timestamptz retention_until
    }
    bookings {
        uuid id PK
        uuid host_id FK
        varchar status "CONFIRMED | CANCELLED | RESCHEDULED"
        timestamptz blocked_start "start - buffer"
        timestamptz blocked_end "end + buffer"
        varchar manage_token_hash "unique"
    }
    webhook_events {
        uuid id PK
        varchar provider
        varchar event_id "unique with provider"
    }
    idempotency_records {
        uuid id PK
        varchar scope
        varchar idempotency_key "unique with scope"
        varchar request_hash
    }
```

## Integrity and concurrency guarantees enforced by the database

| Guarantee | Mechanism |
|---|---|
| No overlapping confirmed bookings per host (buffers included) | `EXCLUDE USING gist (host_id WITH =, tstzrange(blocked_start, blocked_end,'[)') WITH &&) WHERE status='CONFIRMED'` (needs `btree_gist`) |
| One live session per meeting | partial unique index `meeting_rooms(meeting_id) WHERE status='ACTIVE'` |
| One in-flight cloud recording per session | partial unique index `recordings(room_id) WHERE status IN ('STARTING','ACTIVE','STOPPING')` |
| One personal room per user | partial unique index `meetings(host_id) WHERE type='PERSONAL_ROOM'` |
| Webhooks processed once | unique `(provider, event_id)` + `INSERT … ON CONFLICT DO NOTHING` claim |
| Case-insensitive unique e-mail | unique index on `lower(email)` |
| Enumerations and ranges | `CHECK` constraints on every status/role/type column and numeric limits |
| Capacity / admission races | `SELECT … FOR UPDATE` on the meeting row during join, admit and lifecycle changes |
| E-mail delivered once across instances | `SELECT … FOR UPDATE SKIP LOCKED` claim in the dispatcher |

## Indexing for hot paths

| Query | Index |
|---|---|
| Meetings list for a user | `meetings(host_id, start_time)`, `meeting_invitations(user_id)`, `meeting_participants(user_id, created_at)` |
| Seat counting / presence | `meeting_participants(room_id, state)`, `(meeting_id, state)` |
| Busy time for slot computation | `bookings(host_id, start_time)` + the GiST exclusion index, `meetings(host_id, start_time)` |
| Due e-mails | partial `notifications(scheduled_at) WHERE status='PENDING' AND channel='EMAIL'` |
| Audit search | `audit_logs(resource_type, resource_id, created_at)`, `(actor_user_id, created_at)`, `(action, created_at)` |
| Retention job | partial `recordings(retention_until) WHERE status='AVAILABLE'` |

## Migrations

| Version | Content |
|---|---|
| V1 | Baseline: all tables, constraints, indexes, role/permission reference data |
| V2 | `bookings.external_calendar_provider`, `external_event_id` for calendar sync |
