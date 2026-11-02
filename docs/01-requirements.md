# 01 — Business Requirements and Acceptance Criteria

Source: `docs/00-original-brief.md` (master development prompt). Each requirement has an id used by the implementation
checklist (`05-implementation-checklist.md`) and by tests.

## Actors and roles

| Role | Scope | Can |
|---|---|---|
| SYSTEM_ADMIN | Platform | Everything in `/admin`: users, roles, live meetings, audit logs |
| ORGANIZATION_ADMIN | Organization | Manage organization settings and members; manage the org's recordings |
| HOST | Meeting | Edit/cancel/end, all moderation, assign co-hosts, record, manage recordings |
| CO_HOST | Meeting (live session) | Moderation and recording; end the meeting only while the host is away |
| PARTICIPANT | Meeting | Join, publish audio/video/screen (per policy), chat, share files, play recordings |
| GUEST | Meeting | Join through a link if allowed; publish and chat; no file sharing |

Roles are always resolved on the server from the database. Client-supplied roles are ignored.

## Functional requirements

### Identity (ID)
| Id | Requirement | Acceptance criteria |
|---|---|---|
| ID-1 | Registration with e-mail verification | New account cannot sign in until the link from the e-mail is used; links are single-use and expire (48 h) |
| ID-2 | Login, refresh, logout, logout everywhere | Access token ≤ 15 min; refresh token rotates on every use; a replayed old refresh token is rejected and, outside a 30 s race window, revokes the session |
| ID-3 | Password reset | Always answers 202 (no account enumeration); link valid 1 h; completing a reset revokes all sessions |
| ID-4 | Brute-force protection | 10 failed logins lock the credential for 15 min; per-IP and per-e-mail rate limits |
| ID-5 | OAuth2 sign-in (Google, Microsoft) | Enabled only when configured; an existing account is linked only if the provider asserts a verified e-mail |
| ID-6 | Profile and session management | User can edit name/time zone/avatar, list sessions, revoke one session |

### Organizations (ORG)
| ORG-1 | Create organization; creator becomes admin | |
| ORG-2 | Add/remove members, change roles | The last admin cannot be removed or demoted |
| ORG-3 | Organization policy | Guest access and recording retention defaults apply to the org's meetings |

### Meetings (MTG)
| Id | Requirement | Acceptance criteria |
|---|---|---|
| MTG-1 | Instant, scheduled, one-to-one, recurring (RRULE), personal room | One-to-one capacity is 2; personal room is permanent and reusable |
| MTG-2 | Privacy: link / organization / invitees only | Non-authorized users get `NOT_INVITED`; meeting details return 404 to outsiders |
| MTG-3 | Optional password | Missing → `MEETING_PASSWORD_REQUIRED` (422); wrong → `MEETING_PASSWORD_INVALID`; attempts rate limited |
| MTG-4 | Waiting room and "join before host" | Waiting participants receive no media token until admitted |
| MTG-5 | Participant limit | Seat count is evaluated under a row lock; overflow → `MEETING_FULL` |
| MTG-6 | Invitations | E-mail + ICS invitation; in-app notification for account holders |
| MTG-7 | Edit / reschedule / cancel | Invitees receive updated (REQUEST, higher SEQUENCE) or CANCEL ICS |
| MTG-8 | History | Participants with join/leave times and durations are kept after the meeting ends |

### Real-time media (RTC)
| RTC-1 | Real audio/video over LiveKit SFU | Clients connect with a short-lived token (≤ 10 min) carrying only room-join and allowed publish sources |
| RTC-2 | Camera, mic, audio-only, device selection, screen share, full screen | Audio-only meetings never grant camera/screen sources |
| RTC-3 | Presence and reconnection | JOINED/LEFT state comes from verified SFU webhooks, not from clients |
| RTC-4 | Media never transits the API | Only control calls go to Spring Boot |

### Host controls (MOD)
| MOD-1 | Mute one / mute all (server-side track mute) | Hosts/co-hosts are not muted by mute-all |
| MOD-2 | Request unmute | Server sends a request; it is impossible for the server to unmute anyone |
| MOD-3 | Remove participant | Removed users cannot re-join the session; a stale token connection is ejected on arrival |
| MOD-4 | Admit / deny / admit all | |
| MOD-5 | Lock room | New non-moderator joins fail with `MEETING_LOCKED` |
| MOD-6 | Co-host assignment | Only the host; guests must sign in first; host leaving auto-promotes a participant |
| MOD-7 | Screen-share policy | Changing it updates live participants' permissions in the SFU |
| MOD-8 | End for everyone / leave while continuing | |
| MOD-9 | Moderation audit log | Every action recorded with actor, target, time, trace id |

### Recording (REC)
| REC-1 | Cloud recording via LiveKit Egress to private object storage | Only moderators; one active recording per session |
| REC-2 | Lifecycle tracking | STARTING → ACTIVE → STOPPING → AVAILABLE or FAILED, driven by egress webhooks |
| REC-3 | Playback and download through signed URLs (≤ 15 min) | Every access is logged; outsiders get 404; only host/org admin can download or delete |
| REC-4 | Retention | Daily job deletes recordings past `retention_until` (org setting, default 90 days) |
| REC-5 | Visible to participants | A `recording` data message is broadcast before capture starts and on stop/failure |
| REC-6 | Local recording | Browser MediaRecorder; announced to the room before it starts; aborted if the announcement fails |
| REC-7 | Automatic recording (per meeting, host's choice) | Records the whole room plus one file per participant (own camera + microphone) once they are connected; the join page and the room say the meeting is recorded; each capture is announced before it starts |
| REC-8 | Per-participant access | A signed-in participant sees, plays and downloads only their own file; guests' files and the auto room recording are the host's; the host (and org admins) can list, play, download, stop and delete all |

### Scheduling and booking (SCH)
| SCH-1 | Weekly availability in an IANA time zone, date exceptions | DST-correct; overlapping rules rejected |
| SCH-2 | Booking pages | Duration, slot interval, minimum notice, max advance window, buffers |
| SCH-3 | Public slots in the viewer's time zone | ≤ 31 days per request; host's other meetings and bookings are excluded |
| SCH-4 | Booking confirmation | E-mail + ICS to invitee and host, in-app notification, meeting created automatically |
| SCH-5 | No double booking | PostgreSQL exclusion constraint on (host, blocked range incl. buffers); concurrent requests → exactly one succeeds |
| SCH-6 | Cancel / reschedule through a manage link | Token returned once, stored hashed; past bookings cannot be modified |
| SCH-7 | Reminders | 24 h and 1 h before start; cancelled on reschedule/cancel |
| SCH-8 | Google / Microsoft calendar | OAuth connect; bookings pushed to and removed from the host's calendar |

### Collaboration (COL)
| COL-1 | Chat persisted and relayed by the server | Sender identity is the authenticated participant |
| COL-2 | Hand raise and reactions | Data-channel only (ephemeral) |
| COL-3 | File sharing | ≤ 25 MB, allow-listed type + extension, private storage, signed download URLs |

### Administration (ADM)
| ADM-1 | Platform statistics, users (enable/disable, role), live meetings, audit log search | Disabling a user or changing their role revokes their sessions |

## Non-functional requirements

| Id | Requirement |
|---|---|
| NFR-1 | Capacity: 100 concurrent rooms × 10 participants (1,000 participants) with screen share and recording; must be **measured** (see `08-performance-testing.md`) |
| NFR-2 | API latency targets: p95 < 300 ms for reads, p95 < 500 ms for join; error rate < 1 % under target load |
| NFR-3 | Media quality targets: p95 packet loss < 2 %, p95 jitter < 30 ms, join-to-first-frame < 3 s on a 10 Mbit/s link |
| NFR-4 | Availability: stateless API instances; recovery from an API instance loss without dropping media sessions |
| NFR-5 | Security: see `06-security.md` |
| NFR-6 | Observability: Prometheus metrics, OpenTelemetry traces, structured logs with trace ids |
| NFR-7 | Accessibility: keyboard-operable UI, labelled controls, light/dark themes |

## Out of scope (future modules)
Transcription and AI summaries are explicitly optional future modules.
