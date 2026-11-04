/** DTOs mirroring docs/04-api-contracts.md. */

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export type SystemRole = 'SYSTEM_ADMIN' | 'USER';
export type UserStatus = 'ACTIVE' | 'DISABLED';

export interface UserSummary {
  id: string;
  displayName: string;
  email: string;
  avatarUrl?: string;
}

export interface UserProfile extends UserSummary {
  timeZone: string;
  emailVerified: boolean;
  systemRole: SystemRole;
  status: UserStatus;
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  refreshToken?: string;
  user: UserProfile;
}

export interface RegisterResponse {
  userId: string;
  emailVerificationRequired: boolean;
}

export interface OAuthProvider {
  id: 'google' | 'microsoft';
  authorizationUrl: string;
}

export interface SessionInfo {
  id: string;
  createdAt: string;
  lastUsedAt: string;
  userAgent: string;
  ipAddress: string;
  current: boolean;
}

export type OrgRole = 'ORGANIZATION_ADMIN' | 'MEMBER';

export interface Organization {
  id: string;
  name: string;
  slug: string;
  defaultRecordingRetentionDays: number;
  allowGuests: boolean;
  createdAt: string;
  version: number;
  myRole?: OrgRole;
}

export interface OrgMember {
  user: UserSummary;
  role: OrgRole;
  joinedAt: string;
}

export type MeetingType = 'INSTANT' | 'SCHEDULED' | 'PERSONAL_ROOM' | 'ONE_TO_ONE';
export type MeetingStatus = 'SCHEDULED' | 'LIVE' | 'ENDED' | 'CANCELLED';
export type MediaMode = 'VIDEO' | 'AUDIO_ONLY';
export type Privacy = 'LINK' | 'ORGANIZATION' | 'INVITEES_ONLY';
export type MeetingRole = 'HOST' | 'CO_HOST' | 'PARTICIPANT' | 'GUEST';
export type ScreenSharePolicy = 'EVERYONE' | 'HOSTS_ONLY';
/** EVERYONE_VISIBLE: all participants see and hear each other. HOST_AND_SELF_ONLY: attendees see and hear only the host, co-hosts and themselves (enforced by the media server). */
export type VisibilityMode = 'EVERYONE_VISIBLE' | 'HOST_AND_SELF_ONLY';

export interface Meeting {
  id: string;
  joinCode: string;
  joinUrl: string;
  title: string;
  description?: string;
  type: MeetingType;
  status: MeetingStatus;
  mediaMode: MediaMode;
  privacy: Privacy;
  organizationId?: string;
  /** email may be null/absent (e.g. summaries shown to guests): never rely on it. */
  host: Omit<UserSummary, 'email'> & { email?: string | null };
  startTime?: string;
  endTime?: string;
  timeZone: string;
  recurrenceRule?: string;
  passwordProtected: boolean;
  waitingRoomEnabled: boolean;
  allowGuests: boolean;
  joinBeforeHost: boolean;
  muteOnEntry: boolean;
  maxParticipants: number;
  screenSharePolicy: ScreenSharePolicy;
  locked: boolean;
  chatEnabled: boolean;
  myRole?: MeetingRole;
  createdAt: string;
  version: number;
  /** The room is recorded automatically (one recording following the active speaker); participants must be told before joining. */
  autoRecord: boolean;
  /** Some recording of the live session is running right now (whoever started it). */
  recordingInProgress: boolean;
  /** Absent from older servers: treat as EVERYONE_VISIBLE. */
  visibilityMode?: VisibilityMode;
}

export interface CreateMeetingRequest {
  title: string;
  description?: string;
  type: 'INSTANT' | 'SCHEDULED' | 'ONE_TO_ONE';
  mediaMode?: MediaMode;
  privacy?: Privacy;
  organizationId?: string;
  startTime?: string;
  durationMinutes?: number;
  timeZone: string;
  recurrenceRule?: string;
  password?: string;
  waitingRoomEnabled?: boolean;
  allowGuests?: boolean;
  joinBeforeHost?: boolean;
  muteOnEntry?: boolean;
  maxParticipants?: number;
  screenSharePolicy?: ScreenSharePolicy;
  inviteeEmails?: string[];
  autoRecord?: boolean;
  visibilityMode?: VisibilityMode;
}

export type UpdateMeetingRequest = Partial<Omit<CreateMeetingRequest, 'inviteeEmails'>> & { version: number };

export interface MeetingByCode {
  meetingId: string;
  title: string;
  hostName: string;
  status: MeetingStatus;
  startTime?: string;
  mediaMode: MediaMode;
  passwordRequired: boolean;
  allowGuests: boolean;
  waitingRoomEnabled: boolean;
  autoRecord: boolean;
  recordingInProgress: boolean;
}

export type JoinStatus = 'ADMITTED' | 'WAITING' | 'DENIED';

export interface JoinResponse {
  status: JoinStatus;
  participantId: string;
  waitToken: string;
  role: MeetingRole;
  livekitUrl?: string;
  token?: string;
  roomName?: string;
  /** HOST_AND_SELF_ONLY: second media room (attendees publish there, the stage subscribes) and its token. */
  audienceRoomName?: string;
  audienceToken?: string;
  meeting: Meeting;
}

export type ParticipantState = 'WAITING' | 'ADMITTED' | 'JOINED' | 'LEFT' | 'REMOVED' | 'DENIED';

export interface ParticipantView {
  id: string;
  userId?: string;
  displayName: string;
  role: MeetingRole;
  state: ParticipantState;
  joinedAt?: string;
  leftAt?: string;
  durationSeconds?: number;
}

export interface Invitation {
  id: string;
  email: string;
  status: string;
  sentAt: string;
}

export interface RoomSession {
  id: string;
  roomName: string;
  status: 'ACTIVE' | 'ENDED';
  startedAt: string;
  endedAt?: string;
  peakParticipants: number;
}

export type MuteSource = 'MICROPHONE' | 'CAMERA' | 'SCREEN_SHARE';

export interface ModerationAuditEntry {
  id: string;
  action: string;
  actor?: UserSummary;
  targetParticipantId?: string;
  details: unknown;
  createdAt: string;
}

export type RecordingStatus = 'STARTING' | 'ACTIVE' | 'STOPPING' | 'PROCESSING' | 'AVAILABLE' | 'FAILED' | 'DELETED';

export interface Recording {
  id: string;
  meetingId: string;
  meetingTitle: string;
  /** CLOUD = the whole room; PARTICIPANT = one person's own camera and microphone (older recordings only). */
  kind: 'CLOUD' | 'PARTICIPANT';
  /** grid, or speaker (follows the active speaker). */
  layout?: 'grid' | 'speaker' | null;
  contentType?: string | null;
  /** Recorded in a webinar: shows only the host and co-hosts. */
  stageOnly?: boolean;
  status: RecordingStatus;
  startedAt?: string;
  endedAt?: string;
  durationSeconds?: number;
  sizeBytes?: number;
  failureReason?: string;
  retentionUntil?: string;
  startedBy?: UserSummary;
  autoStarted: boolean;
  participantId?: string;
  /** Set when the recorded person was signed in; absent for guests. */
  subjectUserId?: string;
  subjectName?: string;
}

export interface SignedUrl {
  url: string;
  expiresAt: string;
}

export interface ChatMessage {
  id: string;
  meetingId: string;
  participantId: string;
  senderName: string;
  text: string;
  sentAt: string;
  /** STAGE: a webinar attendee's message, seen only by the host, co-hosts and its sender. */
  audience?: 'ALL' | 'STAGE';
}

/** Someone doing something in the room (recording locally, hand raised). */
export interface RoomActor {
  participantId: string;
  displayName: string;
  role: MeetingRole;
  since?: string | null;
}

/** One attendee's audience room in a HOST_AND_SELF_ONLY session, and a token for it. */
export interface AudienceRoomGrant {
  participantId: string;
  roomName: string;
  token: string;
}

/**
 * GET /meetings/{id}/audience-access — HOST_AND_SELF_ONLY: an attendee gets its own audience room (where it
 * publishes), the host and co-hosts every present attendee's (where they watch, hidden). Empty otherwise.
 */
export interface AudienceAccessView {
  livekitUrl: string;
  rooms: AudienceRoomGrant[];
}

/** GET /meetings/{id}/room-state — the live session as the server knows it. */
export interface RoomStateView {
  /** Identifies the server counters `seq`/`stageSeq` belong to; signals from another epoch mean they were reset. */
  epoch?: string | null;
  /** Latest number of signals sent to everyone (`stream: "all"`). */
  seq: number;
  /** Latest number of signals sent to the stage (`stream: "stage"`); host and co-hosts only. */
  stageSeq?: number;
  participantId: string;
  role: MeetingRole;
  visibilityMode: VisibilityMode;
  /** Media identities of the host and co-hosts present. */
  stageIdentities: string[];
  locked: boolean;
  chatEnabled: boolean;
  screenSharePolicy: ScreenSharePolicy;
  waitingRoomEnabled: boolean;
  cloudRecording: boolean;
  autoRecord: boolean;
  localRecorders: RoomActor[];
  raisedHands: RoomActor[];
  /** Stage only. */
  waitingCount?: number;
  /** People in the session (server count, one per person). */
  presentCount?: number;
  /** Real names of everyone present: stage of a webinar only (the SFU shows attendees as "Attendee"). */
  participants?: RoomActor[];
  /** Meeting-wide media permissions for participants (hosts and co-hosts always may publish). */
  mediaPermissions?: MediaPermissions;
  /** The viewer's own media rights. */
  myMedia?: ParticipantMedia;
  /** Everyone's media rights: the host only. */
  participantMedia?: ParticipantMedia[];
}

export type MediaPermission = 'ALLOWED' | 'RESTRICTED';
/** For one participant: ALLOWED / RESTRICTED override the meeting; DEFAULT follows it again. */
export type MediaOverride = MediaPermission | 'DEFAULT';
export type MediaKindKey = 'microphone' | 'camera' | 'screenShare';

export interface MediaPermissions {
  microphone: MediaPermission;
  camera: MediaPermission;
  screenShare: MediaPermission;
}

/** A participant's overrides (null = follows the meeting) and what it may publish now. */
export interface ParticipantMedia {
  participantId: string;
  microphoneOverride: MediaPermission | null;
  cameraOverride: MediaPermission | null;
  screenShareOverride: MediaPermission | null;
  microphone: boolean;
  camera: boolean;
  screenShare: boolean;
}

export interface SpeakerStat {
  roomId: string;
  participantId: string;
  displayName: string;
  role: MeetingRole;
  speakingMs: number;
  turns: number;
  firstSpokeAt?: string | null;
  lastSpokeAt?: string | null;
}

export interface MeetingSpeakerStats {
  meetingId: string;
  totalSpeakingMs: number;
  speakers: SpeakerStat[];
}

export interface DashboardSummary {
  recentRecordings: Recording[];
  recordingsInProgress: number;
  storageUsedBytes: number;
  speakingHighlights: {
    meetingId: string;
    title: string;
    lastSpokeAt?: string | null;
    totalSpeakingMs: number;
    topSpeakers: { displayName: string; speakingMs: number; sharePercent: number }[];
  }[];
  /** Speaking clips with a file (COMPLETED or INCOMPLETE) of the meetings the user hosts, and their total size. */
  speechClipCount: number;
  speechClipBytes: number;
}

export interface FileMeta {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedBy: string;
  createdAt: string;
}

export type DayOfWeek = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

export interface WeeklyRule {
  dayOfWeek: DayOfWeek;
  startTime: string;
  endTime: string;
}

export interface Availability {
  timeZone: string;
  weeklyRules: WeeklyRule[];
}

export interface AvailabilityException {
  id: string;
  date: string;
  available: boolean;
  startTime?: string;
  endTime?: string;
  reason?: string;
}

export interface BookingPageRequest {
  slug: string;
  title: string;
  description?: string;
  durationMinutes: number;
  slotIntervalMinutes?: number;
  minNoticeMinutes?: number;
  maxAdvanceDays?: number;
  bufferBeforeMinutes?: number;
  bufferAfterMinutes?: number;
  mediaMode?: MediaMode;
  active?: boolean;
}

export interface BookingPage extends BookingPageRequest {
  id: string;
  version: number;
  createdAt?: string;
}

export interface PublicBookingPage {
  slug: string;
  title: string;
  description?: string;
  durationMinutes: number;
  hostName: string;
  hostTimeZone: string;
  mediaMode: MediaMode;
}

export interface Slot {
  start: string;
  end: string;
}

export interface SlotsResponse {
  timeZone: string;
  slots: Slot[];
}

export type BookingStatus = 'CONFIRMED' | 'CANCELLED' | 'RESCHEDULED';

export interface Booking {
  id: string;
  bookingPageSlug: string;
  bookingPageTitle: string;
  status: BookingStatus;
  startTime: string;
  endTime: string;
  inviteeName: string;
  inviteeEmail: string;
  inviteeTimeZone: string;
  notes?: string;
  meetingId: string;
  joinUrl: string;
  createdAt: string;
}

export interface BookingConfirmation extends Booking {
  manageToken: string;
}

export interface CreateBookingRequest {
  slug: string;
  startTime: string;
  inviteeName: string;
  inviteeEmail: string;
  inviteeTimeZone: string;
  notes?: string;
}

export interface Notification {
  id: string;
  type: string;
  title: string;
  body: string;
  link?: string;
  read: boolean;
  createdAt: string;
}

export interface AdminStats {
  users: number;
  organizations: number;
  meetingsToday: number;
  liveMeetings: number;
  liveParticipants: number;
  recordingsStorageBytes: number;
  bookingsToday: number;
}

export type LiveMeeting = Meeting & { participantCount: number };

export interface AuditLog {
  id: string;
  actor?: UserSummary;
  action: string;
  resourceType: string;
  resourceId: string;
  details: unknown;
  ipAddress?: string;
  createdAt: string;
}

export type CalendarProvider = 'GOOGLE' | 'MICROSOFT';

export interface CalendarConnection {
  provider: CalendarProvider;
  /** Provider credentials are configured on the server. */
  available: boolean;
  connected: boolean;
  status?: string;
  accountEmail?: string;
}

/** A speaking clip: one participant, one speaking turn (GET /speech-recordings). The file path never leaves the server. */
export interface SpeechRecording {
  id: string;
  meetingId: string;
  meetingTitle: string;
  participantId: string;
  participantName: string;
  fileName: string;
  fileFormat: string;
  fileSize: number;
  startTime: string;
  endTime?: string | null;
  durationMs?: number | null;
  status: 'RECORDING' | 'COMPLETED' | 'INCOMPLETE' | 'FAILED';
  failureReason?: string | null;
  createdAt: string;
}

/** Filter values; the filter follows Date -> Meeting -> Participant. Dates are folder dates, YYYY-MM-DD, newest first. */
export interface SpeechRecordingFacets {
  /** Meetings with clips, with the dates they have clips on. */
  meetings: { id: string; label: string; dates: string[] }[];
  /**
   * Participants with clips; each is one participation, in the meeting `meetingId`, with the dates it has clips on.
   * `host`: the meeting's host. `label` is the plain name.
   */
  participants: { id: string; label: string; meetingId: string; host: boolean; dates: string[] }[];
  dates: string[];
}

export type SpeechRecordingView = 'hosted' | 'mine';
