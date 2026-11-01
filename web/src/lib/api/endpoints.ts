import { api } from './client';
import type {
  AdminStats,
  AuditLog,
  AuthResponse,
  Availability,
  AvailabilityException,
  CalendarConnection,
  CalendarProvider,
  Booking,
  BookingConfirmation,
  BookingPage,
  BookingPageRequest,
  ChatMessage,
  CreateBookingRequest,
  CreateMeetingRequest,
  DashboardSummary,
  FileMeta,
  Invitation,
  JoinResponse,
  LiveMeeting,
  Meeting,
  MeetingByCode,
  MeetingSpeakerStats,
  ModerationAuditEntry,
  MuteSource,
  Notification,
  OAuthProvider,
  OrgMember,
  OrgRole,
  Organization,
  Page,
  ParticipantView,
  PublicBookingPage,
  Recording,
  RegisterResponse,
  RoomSession,
  AudienceAccessView,
  RoomStateView,
  SpeechRecording,
  SpeechRecordingFacets,
  SpeechRecordingView,
  ScreenSharePolicy,
  SessionInfo,
  SignedUrl,
  SlotsResponse,
  UpdateMeetingRequest,
  UserProfile,
  WeeklyRule,
} from './types';

const enc = encodeURIComponent;

/** Header carrying the participant secret (JoinResponse.waitToken) on participant-scoped calls. */
export function participantHeaders(waitToken?: string | null): Record<string, string> {
  return waitToken ? { 'X-Participant-Token': waitToken } : {};
}

// ---- Auth -------------------------------------------------------------------------------------
export const authApi = {
  register: (body: { email: string; password: string; displayName: string; timeZone: string }) =>
    api.post<RegisterResponse>('/auth/register', body, { auth: 'none' }),
  login: (body: { email: string; password: string }) =>
    api.post<AuthResponse>('/auth/login', { ...body, clientType: 'WEB' }, { auth: 'none' }),
  logout: () => api.post<void>('/auth/logout', {}, { auth: 'optional' }),
  logoutAll: () => api.post<void>('/auth/logout-all'),
  verifyEmail: (token: string) => api.post<void>('/auth/verify-email', { token }, { auth: 'none' }),
  resendVerification: (email: string) => api.post<void>('/auth/resend-verification', { email }, { auth: 'none' }),
  requestPasswordReset: (email: string) =>
    api.post<void>('/auth/password-reset/request', { email }, { auth: 'none' }),
  confirmPasswordReset: (token: string, newPassword: string) =>
    api.post<void>('/auth/password-reset/confirm', { token, newPassword }, { auth: 'none' }),
  oauthProviders: () => api.get<OAuthProvider[]>('/auth/oauth2/providers', { auth: 'none' }),
};

// ---- Users ------------------------------------------------------------------------------------
export const usersApi = {
  me: () => api.get<UserProfile>('/users/me'),
  updateMe: (body: { displayName?: string; timeZone?: string; avatarUrl?: string }) =>
    api.patch<UserProfile>('/users/me', body),
  changePassword: (body: { currentPassword: string; newPassword: string }) =>
    api.post<void>('/users/me/password', body),
  sessions: () => api.get<SessionInfo[]>('/users/me/sessions'),
  revokeSession: (id: string) => api.delete(`/users/me/sessions/${enc(id)}`),
};

// ---- Organizations ----------------------------------------------------------------------------
export const orgsApi = {
  list: () => api.get<Organization[]>('/organizations'),
  get: (id: string) => api.get<Organization>(`/organizations/${enc(id)}`),
  create: (body: { name: string; slug: string }) => api.post<Organization>('/organizations', body),
  update: (
    id: string,
    body: { name?: string; defaultRecordingRetentionDays?: number; allowGuests?: boolean; version: number },
  ) => api.patch<Organization>(`/organizations/${enc(id)}`, body),
  members: (id: string) => api.get<OrgMember[]>(`/organizations/${enc(id)}/members`),
  addMember: (id: string, body: { email: string; role: OrgRole }) =>
    api.post<void>(`/organizations/${enc(id)}/members`, body),
  updateMember: (id: string, userId: string, role: OrgRole) =>
    api.patch<void>(`/organizations/${enc(id)}/members/${enc(userId)}`, { role }),
  removeMember: (id: string, userId: string) => api.delete(`/organizations/${enc(id)}/members/${enc(userId)}`),
};

// ---- Meetings ---------------------------------------------------------------------------------
export type MeetingScope = 'upcoming' | 'past' | 'live' | 'all';

export const meetingsApi = {
  list: (scope: MeetingScope, page = 0, size = 20) =>
    api.get<Page<Meeting>>('/meetings', { query: { scope, page, size } }),
  get: (id: string) => api.get<Meeting>(`/meetings/${enc(id)}`),
  create: (body: CreateMeetingRequest, idempotencyKey?: string) =>
    api.post<Meeting>('/meetings', body, {
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    }),
  update: (id: string, body: UpdateMeetingRequest) => api.patch<Meeting>(`/meetings/${enc(id)}`, body),
  cancel: (id: string, reason?: string) => api.post<void>(`/meetings/${enc(id)}/cancel`, { reason }),
  byCode: (joinCode: string) => api.get<MeetingByCode>(`/meetings/by-code/${enc(joinCode)}`, { auth: 'optional' }),
  join: (id: string, body: { password?: string; displayName?: string }) =>
    api.post<JoinResponse>(`/meetings/${enc(id)}/join`, body, { auth: 'optional' }),
  joinStatus: (id: string, participantId: string, waitToken: string) =>
    api.get<JoinResponse>(`/meetings/${enc(id)}/join-status`, {
      // The participant secret travels only in the X-Participant-Token header (kept out of URLs and access logs).
      query: { participantId },
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  leave: (id: string, participantId: string, waitToken?: string) =>
    api.post<void>(`/meetings/${enc(id)}/leave`, { participantId }, {
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  end: (id: string) => api.post<void>(`/meetings/${enc(id)}/end`),
  participants: (id: string, waitToken?: string) =>
    api.get<ParticipantView[]>(`/meetings/${enc(id)}/participants`, {
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  invitations: (id: string) => api.get<Invitation[]>(`/meetings/${enc(id)}/invitations`),
  invite: (id: string, emails: string[]) => api.post<void>(`/meetings/${enc(id)}/invitations`, { emails }),
};

export const roomsApi = {
  personal: () => api.get<Meeting>('/rooms/personal'),
  updatePersonal: (body: UpdateMeetingRequest) => api.patch<Meeting>('/rooms/personal', body),
  sessions: (meetingId: string) => api.get<RoomSession[]>(`/rooms/${enc(meetingId)}/sessions`),
};

// ---- Moderation -------------------------------------------------------------------------------
const mod = (meetingId: string) => `/meetings/${enc(meetingId)}/moderation`;

export const moderationApi = {
  waiting: (meetingId: string) => api.get<ParticipantView[]>(`${mod(meetingId)}/waiting`),
  admit: (meetingId: string, pid: string) => api.post<void>(`${mod(meetingId)}/waiting/${enc(pid)}/admit`),
  deny: (meetingId: string, pid: string) => api.post<void>(`${mod(meetingId)}/waiting/${enc(pid)}/deny`),
  admitAll: (meetingId: string) => api.post<void>(`${mod(meetingId)}/waiting/admit-all`),
  mute: (meetingId: string, pid: string, source: MuteSource) =>
    api.post<void>(`${mod(meetingId)}/participants/${enc(pid)}/mute`, { source }),
  muteAll: (meetingId: string) => api.post<void>(`${mod(meetingId)}/mute-all`),
  requestUnmute: (meetingId: string, pid: string, source: 'MICROPHONE' | 'CAMERA') =>
    api.post<void>(`${mod(meetingId)}/participants/${enc(pid)}/request-unmute`, { source }),
  remove: (meetingId: string, pid: string) => api.post<void>(`${mod(meetingId)}/participants/${enc(pid)}/remove`),
  setRole: (meetingId: string, pid: string, role: 'CO_HOST' | 'PARTICIPANT') =>
    api.post<void>(`${mod(meetingId)}/participants/${enc(pid)}/role`, { role }),
  lock: (meetingId: string, locked: boolean) => api.post<void>(`${mod(meetingId)}/lock`, { locked }),
  settings: (
    meetingId: string,
    body: { screenSharePolicy?: ScreenSharePolicy; waitingRoomEnabled?: boolean; chatEnabled?: boolean },
  ) => api.put<Meeting>(`${mod(meetingId)}/settings`, body),
  audit: (meetingId: string, page = 0, size = 20) =>
    api.get<Page<ModerationAuditEntry>>(`${mod(meetingId)}/audit`, { query: { page, size } }),
};

// ---- Recordings -------------------------------------------------------------------------------
export const recordingsApi = {
  start: (meetingId: string, body: { layout?: 'grid' | 'speaker'; audioOnly?: boolean } = {}) =>
    api.post<Recording>(`/meetings/${enc(meetingId)}/recordings`, body),
  stop: (meetingId: string, recordingId: string) =>
    api.post<Recording>(`/meetings/${enc(meetingId)}/recordings/${enc(recordingId)}/stop`),
  /** Participants may call this too (guests with their participant token): never redirects to /login. */
  forMeeting: (meetingId: string, waitToken?: string) =>
    api.get<Recording[]>(`/meetings/${enc(meetingId)}/recordings`, { auth: 'optional', headers: participantHeaders(waitToken) }),
  localState: (meetingId: string, participantId: string, state: 'STARTED' | 'STOPPED', waitToken?: string) =>
    api.post<void>(`/meetings/${enc(meetingId)}/local-recordings`, { participantId, state }, {
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  library: (page = 0, size = 20) => api.get<Page<Recording>>('/recordings', { query: { page, size } }),
  get: (id: string) => api.get<Recording>(`/recordings/${enc(id)}`),
  playbackUrl: (id: string) => api.post<SignedUrl>(`/recordings/${enc(id)}/playback-url`),
  downloadUrl: (id: string) => api.post<SignedUrl>(`/recordings/${enc(id)}/download-url`),
  remove: (id: string) => api.delete(`/recordings/${enc(id)}`),
};

// ---- Live room state, hands, reactions, speaking time --------------------------------------------
export const roomStateApi = {
  get: (meetingId: string, participantId: string, waitToken?: string) =>
    api.get<RoomStateView>(`/meetings/${enc(meetingId)}/room-state`, {
      query: { participantId },
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  /** HOST_AND_SELF_ONLY: credentials for the audience rooms this participant may connect to (own, or every attendee's). */
  audienceAccess: (meetingId: string, participantId: string, waitToken?: string) =>
    api.get<AudienceAccessView>(`/meetings/${enc(meetingId)}/audience-access`, {
      query: { participantId },
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  hand: (meetingId: string, participantId: string, raised: boolean, waitToken?: string) =>
    api.post<void>(`/meetings/${enc(meetingId)}/signals/hand`, { participantId, raised }, {
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  reaction: (meetingId: string, participantId: string, emoji: string, waitToken?: string) =>
    api.post<void>(`/meetings/${enc(meetingId)}/signals/reaction`, { participantId, emoji }, {
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
};

export const speakerStatsApi = {
  report: (
    meetingId: string,
    body: { participantId: string; batchSeq: number; entries: { participantId: string; speakingMs: number; turns: number }[] },
  ) => api.post<void>(`/meetings/${enc(meetingId)}/speaker-stats`, body),
  forMeeting: (meetingId: string) => api.get<MeetingSpeakerStats>(`/meetings/${enc(meetingId)}/speaker-stats`),
};

export const dashboardApi = {
  summary: () => api.get<DashboardSummary>('/dashboard/summary'),
};

// ---- Chat & files -----------------------------------------------------------------------------
export const chatApi = {
  history: (meetingId: string, waitToken?: string, before?: string, limit = 50) =>
    api.get<ChatMessage[]>(`/meetings/${enc(meetingId)}/chat`, {
      query: { before, limit },
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  send: (meetingId: string, participantId: string, text: string, waitToken?: string) =>
    api.post<ChatMessage>(`/meetings/${enc(meetingId)}/chat`, { participantId, text }, {
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  files: (meetingId: string, waitToken?: string) =>
    api.get<FileMeta[]>(`/meetings/${enc(meetingId)}/files`, {
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  upload: (meetingId: string, participantId: string, file: File, waitToken?: string) => {
    const form = new FormData();
    form.append('file', file);
    form.append('participantId', participantId);
    return api.post<FileMeta>(`/meetings/${enc(meetingId)}/files`, form, {
      auth: 'optional',
      headers: participantHeaders(waitToken),
    });
  },
  fileUrl: (meetingId: string, fileId: string, waitToken?: string) =>
    api.post<SignedUrl>(`/meetings/${enc(meetingId)}/files/${enc(fileId)}/download-url`, undefined, {
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
};

// ---- Scheduling -------------------------------------------------------------------------------
export const availabilityApi = {
  get: () => api.get<Availability>('/availability'),
  put: (body: { timeZone: string; weeklyRules: WeeklyRule[] }) => api.put<Availability>('/availability', body),
  exceptions: (from: string, to: string) =>
    api.get<AvailabilityException[]>('/availability/exceptions', { query: { from, to } }),
  addException: (body: Omit<AvailabilityException, 'id'>) =>
    api.post<AvailabilityException>('/availability/exceptions', body),
  deleteException: (id: string) => api.delete(`/availability/exceptions/${enc(id)}`),
};

export const bookingPagesApi = {
  list: () => api.get<BookingPage[]>('/booking-pages'),
  get: (id: string) => api.get<BookingPage>(`/booking-pages/${enc(id)}`),
  create: (body: BookingPageRequest) => api.post<BookingPage>('/booking-pages', body),
  update: (id: string, body: Partial<BookingPageRequest> & { version: number }) =>
    api.patch<BookingPage>(`/booking-pages/${enc(id)}`, body),
  remove: (id: string) => api.delete(`/booking-pages/${enc(id)}`),
  publicPage: (slug: string) => api.get<PublicBookingPage>(`/booking-pages/public/${enc(slug)}`, { auth: 'none' }),
  slots: (slug: string, from: string, to: string, timeZone: string) =>
    api.get<SlotsResponse>(`/booking-pages/public/${enc(slug)}/slots`, {
      query: { from, to, timeZone },
      auth: 'none',
    }),
};

export const bookingsApi = {
  create: (body: CreateBookingRequest, idempotencyKey: string) =>
    api.post<BookingConfirmation>('/bookings', body, {
      auth: 'none',
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  list: (scope: 'upcoming' | 'past', page = 0, size = 20) =>
    api.get<Page<Booking>>('/bookings', { query: { scope, page, size } }),
  get: (id: string) => api.get<Booking>(`/bookings/${enc(id)}`),
  cancel: (id: string, reason?: string) => api.post<void>(`/bookings/${enc(id)}/cancel`, { reason }),
  manage: (token: string) => api.get<Booking>(`/bookings/manage/${enc(token)}`, { auth: 'none' }),
  manageCancel: (token: string, reason?: string) =>
    api.post<void>(`/bookings/manage/${enc(token)}/cancel`, { reason }, { auth: 'none' }),
  manageReschedule: (token: string, startTime: string) =>
    api.post<BookingConfirmation>(`/bookings/manage/${enc(token)}/reschedule`, { startTime }, { auth: 'none' }),
};

// ---- Calendar integrations ------------------------------------------------------------------
export const calendarApi = {
  connections: () => api.get<CalendarConnection[]>('/calendar/connections'),
  authorize: (provider: CalendarProvider) =>
    api.post<{ authorizationUrl: string }>(`/calendar/connections/${enc(provider)}/authorize`),
  disconnect: (provider: CalendarProvider) => api.delete(`/calendar/connections/${enc(provider)}`),
};

// ---- Notifications ----------------------------------------------------------------------------
export const notificationsApi = {
  list: (opts: { unreadOnly?: boolean; page?: number; size?: number } = {}) =>
    api.get<Page<Notification>>('/notifications', {
      query: { unreadOnly: opts.unreadOnly ? true : undefined, page: opts.page ?? 0, size: opts.size ?? 10 },
    }),
  markRead: (id: string) => api.post<void>(`/notifications/${enc(id)}/read`),
  markAllRead: () => api.post<void>('/notifications/read-all'),
};

// ---- Admin ------------------------------------------------------------------------------------
export const adminApi = {
  stats: () => api.get<AdminStats>('/admin/stats'),
  users: (q: string, page = 0, size = 20) =>
    api.get<Page<UserProfile>>('/admin/users', { query: { q, page, size } }),
  updateUser: (id: string, body: { status?: 'ACTIVE' | 'DISABLED'; systemRole?: 'SYSTEM_ADMIN' | 'USER' }) =>
    api.patch<UserProfile>(`/admin/users/${enc(id)}`, body),
  liveMeetings: () => api.get<LiveMeeting[]>('/admin/meetings/live'),
  auditLogs: (f: { actorId?: string; action?: string; from?: string; to?: string; page?: number; size?: number }) =>
    api.get<Page<AuditLog>>('/admin/audit-logs', {
      query: { actorId: f.actorId, action: f.action, from: f.from, to: f.to, page: f.page ?? 0, size: f.size ?? 20 },
    }),
};

// ---- Speaking clips (recorded by each participant's own browser) ---------------------------------
export const speechRecordingsApi = {
  start: (meetingId: string, participantId: string, format: string, waitToken?: string) =>
    api.post<SpeechRecording>(`/meetings/${enc(meetingId)}/speech-recordings`, { participantId, format }, {
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  chunk: (meetingId: string, recordingId: string, seq: number, participantId: string, data: Blob, waitToken?: string) =>
    api.put<SpeechRecording>(`/meetings/${enc(meetingId)}/speech-recordings/${enc(recordingId)}/chunks/${seq}`, data, {
      query: { participantId },
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  finish: (meetingId: string, recordingId: string, participantId: string, chunks: number, waitToken?: string) =>
    api.post<SpeechRecording>(`/meetings/${enc(meetingId)}/speech-recordings/${enc(recordingId)}/finish`, { participantId, chunks }, {
      auth: 'optional',
      headers: participantHeaders(waitToken),
    }),
  list: (q: { view: SpeechRecordingView; date?: string; meetingId?: string; participantId?: string; page?: number; size?: number }) =>
    api.get<Page<SpeechRecording>>('/speech-recordings', { query: q }),
  facets: (view: SpeechRecordingView) => api.get<SpeechRecordingFacets>('/speech-recordings/facets', { query: { view } }),
  accessUrl: (id: string, download: boolean) =>
    api.post<{ url: string; expiresAt: string }>(`/speech-recordings/${enc(id)}/access-url`, undefined, { query: { download } }),
};
