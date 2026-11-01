# MASTER DEVELOPMENT PROMPT — HIGH-PERFORMANCE VIDEO MEETING PLATFORM

## 1. Your Role

Act as a Principal Software Architect, Senior Business Analyst, Senior Java Developer, React Developer, WebRTC Engineer, Database Architect, DevOps Engineer, and QA Engineer.

Build a complete, production-oriented video conferencing and meeting scheduling platform inspired by Zoom, Microsoft Teams, and Calendly.

The application must support web, Android, iOS, and desktop platforms, with real-time video and voice calls, meeting rooms, host controls, screen sharing, cloud and local recording, scheduling, public booking pages, and administration.

Do not build a static prototype. Implement working features, persistent storage, real-time media integration, security, automated tests, deployment configuration, and documentation.

Never claim a feature is complete if it only uses mock data, hard-coded responses, simulated video, or placeholder buttons.

## 2. Final Technology Stack

Use the following architecture without changing the core technologies unless a documented technical limitation requires it.

### Frontend
- React
- TypeScript
- Vite
- Tailwind CSS
- shadcn/ui
- React Router
- TanStack Query
- React Hook Form
- Zod
- LiveKit React SDK

### Mobile
- React Native
- TypeScript
- Official compatible LiveKit React Native SDK
- Native device permissions and push notification integrations

### Desktop
- Tauri or Electron with React and TypeScript
- Select the framework after evaluating screen capture, camera/microphone access, security, and cross-platform packaging requirements.
- Do not implement desktop recording or device controls using unsupported browser assumptions.

### Backend
- Java LTS
- Compatible stable Spring Boot release
- Spring Security
- Spring Data JPA
- Hibernate
- Bean Validation
- Maven
- Flyway
- OpenAPI / Swagger
- WebSockets or STOMP for application-level events when required

### Real-Time Media
- Self-hosted LiveKit SFU
- WebRTC
- LiveKit server SDK for the selected backend integration
- LiveKit Egress for cloud recording
- TURN/TLS and UDP connectivity as required

### Data and Infrastructure
- PostgreSQL
- Redis
- MinIO or S3-compatible object storage
- Docker
- Docker Compose for development
- Kubernetes when production operational requirements justify it
- GitHub Actions
- OpenTelemetry
- Prometheus
- Grafana

Use a modular monolith for the business backend. Keep media infrastructure and recording workers independently deployable.

## 3. Mandatory Business Requirements

### Authentication and authorization
Implement registration, login, password reset, email verification, OAuth2 configuration, profile management, organization membership, role-based authorization, and secure session management.

Roles:
- SYSTEM_ADMIN
- ORGANIZATION_ADMIN
- HOST
- CO_HOST
- PARTICIPANT
- GUEST

Enforce authorization on the backend and media platform. Never trust client-supplied roles or permissions.

### Meeting management
Implement:
- Instant meetings.
- Scheduled meetings.
- Personal meeting rooms.
- Private meetings.
- One-to-one calls.
- Group meetings.
- Voice-only calls.
- Video calls.
- Meeting invitations.
- Recurring meetings.
- Secure join links.
- Meeting passwords where configured.
- Waiting rooms.
- Participant limits.
- Meeting history.
- Meeting cancellation and rescheduling.

### Real-time communication
Implement actual LiveKit-powered communication:
- Camera on/off.
- Microphone mute/unmute.
- Audio-only mode.
- Device selection.
- Participant gallery.
- Active speaker view.
- Connection status.
- Participant join and leave events.
- Reconnection handling.
- Screen sharing.
- Full-screen mode.
- Responsive meeting controls.

Use actual media tracks. Do not simulate video using placeholder images.

### Host controls
Implement:
- Mute individual participants where supported.
- Mute all participants.
- Request participants to unmute.
- Participant removal.
- Waiting-room admission.
- Room locking.
- Co-host assignment.
- Screen-sharing permissions.
- Recording controls.
- End meeting for everyone.
- Leave while allowing the meeting to continue when permitted.
- Host moderation audit logs.

A host must never silently activate a participant's microphone. Require participant action or consent to unmute.

### Recording
Implement both recording modes.

Cloud recording:
- Start and stop authorized recording.
- Use LiveKit Egress or the appropriate supported recording workflow.
- Store recordings in private object storage.
- Track recording lifecycle and failures.
- Provide playback and authorized downloads.
- Use temporary signed URLs or authorized streaming.
- Support retention and deletion.
- Show recording status to participants.

Local recording:
- Implement on clients that support the required media capture and recording capabilities.
- Use browser or native media APIs where appropriate.
- Save the recording to the user's device.
- Clearly indicate local recording status.
- Handle storage limitations, permissions, interrupted recordings, and export failures.
- Do not promise system-audio capture on platforms where it is unsupported.

Recording must be visible to participants and comply with applicable privacy and consent requirements.

### Calendly-style scheduling
Implement:
- Weekly availability.
- Custom date availability.
- Time zones.
- Minimum booking notice.
- Maximum advance booking window.
- Buffer periods.
- Public booking pages.
- Available time slots.
- Booking confirmation.
- Cancellation.
- Rescheduling.
- Recurring scheduling where supported.
- Calendar invitations.
- Email reminders.
- Google Calendar and Microsoft Outlook integrations through configurable adapters.

Prevent double bookings through database-level or equivalent concurrency-safe protection.

### Collaboration
Implement:
- In-meeting chat.
- Participant list.
- Hand raising.
- Emoji reactions.
- Secure file sharing.
- Optional meeting notes.
- Meeting history and participant summaries.

Keep transcription and AI summaries as optional future modules.

## 4. Required Backend Structure

Use this package structure:

com.example.meetplatform
├── MeetPlatformApplication.java
├── common
│   ├── config
│   ├── security
│   ├── exception
│   ├── response
│   ├── validation
│   ├── audit
│   └── observability
├── identity
├── organization
├── meeting
├── room
├── participant
├── moderation
├── media
│   ├── service
│   ├── adapter
│   │   └── livekit
│   ├── dto
│   └── webhook
├── recording
├── scheduling
├── booking
├── calendar
├── notification
├── chat
├── file
└── administration

For each business module, introduce controller, service, repository, entity, DTO, and mapper packages as appropriate.

Follow these architectural rules:
- Controllers must remain thin.
- Business logic belongs in application services.
- Never expose JPA entities directly through REST APIs.
- Use request and response DTOs.
- Use constructor injection.
- Use Flyway migrations.
- Use explicit transaction boundaries.
- Use interfaces to isolate external providers where beneficial.
- Avoid giant service classes and circular dependencies.
- Avoid unnecessary microservices.
- Keep all business operations independently testable.

## 5. Database Requirements

Design and implement PostgreSQL tables for:
- users
- user_credentials or external_identity_links
- organizations
- organization_members
- roles and permissions
- meetings
- meeting_participants
- meeting_rooms
- meeting_invitations
- availability_rules
- availability_exceptions
- booking_pages
- bookings
- calendar_connections
- recordings
- recording_access_logs
- notifications
- chat_messages
- file_metadata
- audit_logs
- webhook_events
- idempotency_records

Use UUID primary keys, foreign keys, appropriate unique constraints, indexes, timestamps, and optimistic locking where useful.

Store recording files in object storage, not PostgreSQL BLOB columns.

Use PostgreSQL transactions and exclusion constraints or another proven concurrency-safe strategy to prevent overlapping confirmed bookings.

Use UTC for stored instants and preserve IANA time zones for scheduling rules.

Keep live media state in LiveKit and use validated events to synchronize durable application state.

## 6. API Requirements

Use the prefix /api/v1.

Implement REST API groups for:

Authentication:
- /api/v1/auth
- /api/v1/users

Meetings:
- /api/v1/meetings
- /api/v1/meetings/{meetingId}/join
- /api/v1/meetings/{meetingId}/leave
- /api/v1/meetings/{meetingId}/end

Rooms and moderation:
- /api/v1/rooms
- /api/v1/meetings/{meetingId}/moderation

Recordings:
- /api/v1/meetings/{meetingId}/recordings
- /api/v1/recordings/{recordingId}

Scheduling:
- /api/v1/availability
- /api/v1/booking-pages
- /api/v1/bookings

Administration:
- /api/v1/admin

Define exact HTTP methods, request DTOs, response DTOs, validation rules, authorization rules, and error codes.

Generate OpenAPI documentation.

For meeting joins, authenticate the user, authorize the meeting, and issue a short-lived LiveKit access token containing only the required room and participant permissions.

Never expose media server API secrets or signing keys to clients.

Verify webhook signatures and handle duplicate events idempotently.

## 7. Frontend Pages

Implement:
- Login and registration.
- Dashboard.
- Upcoming meetings.
- Meeting history.
- Create and edit meeting.
- Meeting details.
- Join by link.
- Pre-join device testing.
- Live meeting room.
- Host moderation panel.
- Participant list.
- Chat panel.
- Screen-sharing interface.
- Recording library.
- Recording playback.
- Scheduling calendar.
- Availability settings.
- Public booking page.
- Booking confirmation.
- Rescheduling and cancellation.
- User settings.
- Organization settings.
- Administration dashboard.
- User and role management.
- Audit log viewer.

Create an original, modern SaaS design with responsive layouts, accessible controls, dark/light themes, loading states, empty states, error handling, and confirmation dialogs.

All buttons must perform their intended action.

## 8. Performance and Capacity

Initial business target:
- 100 concurrent active meeting rooms.
- Use 10 participants per room as the initial stress-test scenario.
- Validate up to 1,000 concurrent participants across those rooms.
- Test concurrent screen sharing and cloud recording.
- Establish explicit API latency, media quality, reliability, and recovery targets before release.

Do not assume the capacity target is met without measurements.

Optimize through:
- Stateless Spring Boot instances.
- Indexed database queries.
- Bounded connection pools.
- Redis caching where useful.
- Asynchronous notification and recording processing.
- Timeouts, retries, and backpressure.
- Efficient media subscription strategies.
- Independently scaled media and recording workers.
- Metrics, distributed tracing, and load tests.

Do not route media streams through Spring Boot REST endpoints.

Do not add Kafka or Kubernetes merely for appearance. Introduce them when load, durability, deployment, or operational requirements justify their complexity.

## 9. Security Requirements

Implement:
- HTTPS.
- Authentication and resource-level authorization.
- Appropriate CSRF protection.
- Strict CORS.
- Rate limiting.
- Secure password hashing.
- Short-lived media tokens.
- Secret management.
- Signed recording access.
- Webhook verification.
- Audit logging.
- Input validation.
- Recording retention and deletion.
- Dependency vulnerability checks.
- Session revocation.
- Protection against meeting-link abuse.

Never log passwords, tokens, provider secrets, or sensitive recording URLs.

## 10. Testing Requirements

Use:
- JUnit 5.
- Mockito.
- Testcontainers.
- React Testing Library.
- Playwright or an equivalent end-to-end framework.

Implement:
- Unit tests.
- API integration tests.
- Repository tests.
- Authentication tests.
- Authorization tests.
- Meeting lifecycle tests.
- Host control tests.
- Booking concurrency tests.
- Time-zone and daylight-saving tests.
- Recording lifecycle tests.
- Webhook idempotency tests.
- Browser-based multi-device media tests.
- Performance and recovery tests.

Verify that unauthorized users cannot join private rooms, perform host actions, or access recordings.

Verify that concurrent booking requests cannot create overlapping confirmed bookings.

Verify that failed recordings and disconnected participants are handled correctly.

## 11. Deployment Requirements

Provide:
- Dockerfiles.
- Docker Compose.
- PostgreSQL configuration.
- Redis configuration.
- Self-hosted LiveKit configuration.
- Egress worker configuration.
- Object storage configuration.
- Environment variable templates.
- Reverse proxy and HTTPS guidance.
- Health and readiness endpoints.
- CI/CD workflow.
- Metrics and alerts.
- Backup and restore instructions.
- Production deployment documentation.

Production WebRTC must have the required firewall rules, advertised media addresses, UDP connectivity, TLS configuration, and TURN fallback where required.

Never commit real secrets to source control.

## 12. Implementation Order

Phase 1: Requirements, architecture, ERD, OpenAPI contracts, repository structure, and deployment plan.

Phase 2: Project foundation, authentication, security, database migrations, and CI.

Phase 3: Meeting creation, secure joining, LiveKit integration, real audio/video, and participant lifecycle.

Phase 4: Host moderation, screen sharing, cloud recording, local recording, and recording history.

Phase 5: Scheduling, availability, public booking pages, concurrency protection, and notifications.

Phase 6: Mobile and desktop clients.

Phase 7: Administration, observability, security hardening, load testing, and production deployment.

## 13. Mandatory Working Method

Start by inspecting the existing repository. If no project exists, create the frontend and backend projects with clear setup instructions.

Before implementation:
1. Produce the requirements document.
2. Produce the architecture diagram.
3. Produce the database ERD.
4. Define API contracts.
5. Create the implementation checklist.

Then implement a complete vertical slice:

Create meeting → generate secure join token → join from two devices → exchange actual audio/video → apply host controls → end meeting → persist history.

Run the relevant tests and fix failures before moving on.

Continue incrementally through the remaining phases. Preserve the implementation checklist and update it as features are completed.

If credentials or infrastructure are unavailable, implement the genuine integration boundary, explain the required configuration, and test everything that can be tested locally.

Do not replace real functionality with mock implementations without clearly identifying the limitation.

## 14. Definition of Done

The project is ready for release only when:
- The frontend and backend build successfully.
- Database migrations execute successfully.
- Authentication and authorization work.
- Real-time audio/video works between separate clients.
- Host controls enforce permissions.
- Screen sharing works on supported platforms.
- Cloud and local recording work within their documented platform limitations.
- Booking conflicts are prevented.
- Automated tests pass.
- The agreed capacity test passes.
- Security and recovery checks pass.
- Deployment documentation is complete.
- Remaining limitations are explicitly documented.

## 15. Required Deliverables

Deliver:
1. Complete source code.
2. Business requirements and acceptance criteria.
3. Architecture and database diagrams.
4. Database migrations.
5. REST API documentation.
6. Frontend and backend implementations.
7. WebRTC and LiveKit integration.
8. Recording integration.
9. Scheduling and booking.
10. Mobile and desktop applications.
11. Automated tests.
12. Docker and CI/CD configuration.
13. Security documentation.
14. Performance test results.
15. Setup and deployment guides.
16. A final feature checklist and list of known limitations.

Begin now with Phase 1 and the initial project foundation. After completing each stage, report the files created, features implemented, tests executed, configuration required, and next tasks. Continue implementing rather than stopping after generating a plan or boilerplate.
