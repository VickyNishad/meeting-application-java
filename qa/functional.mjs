// End-to-end API scenarios against the live local stack (real PostgreSQL + real LiveKit).
// Usage: node qa/functional.mjs [baseUrl]   (default: QA_BASE_URL, else the local API on 8081)
import { BASE as DEFAULT_BASE } from './lib.mjs';

const BASE = process.argv[2] || DEFAULT_BASE;
const results = [];
let section = '';

function rid() { return Math.random().toString(36).slice(2, 8); }

async function call(method, path, { body, token, headers = {}, raw = false } = {}) {
  const h = { ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  let payload;
  if (body instanceof FormData) payload = body;
  else if (body !== undefined) { h['Content-Type'] = 'application/json'; payload = typeof body === 'string' ? body : JSON.stringify(body); }
  const res = await fetch(BASE + path, { method, headers: h, body: payload, redirect: 'manual' });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: res.status, json, text, headers: res.headers };
}

function check(name, cond, detail = '') {
  results.push({ section, name, ok: !!cond, detail: cond ? '' : detail });
}

function expectStatus(name, r, status, code) {
  const ok = r.status === status && (!code || r.json?.code === code);
  check(name, ok, `expected ${status}${code ? ' ' + code : ''}, got ${r.status} ${r.json?.code ?? ''} ${r.text.slice(0, 300)}`);
  return r;
}

async function user(name, extra = {}) {
  const email = `${name.toLowerCase().replace(/\s/g, '.')}+${rid()}@example.test`;
  const password = 'a long local test passphrase';
  const reg = await call('POST', '/auth/register', { body: { email, password, displayName: name, timeZone: 'Asia/Kolkata', ...extra } });
  if (reg.status !== 201) throw new Error('register failed ' + reg.text);
  const login = await call('POST', '/auth/login', { body: { email, password, clientType: 'NATIVE' } });
  return { email, password, id: reg.json.userId, token: login.json.accessToken, refresh: login.json.refreshToken };
}

const iso = (d) => new Date(d).toISOString().replace(/\.\d{3}Z$/, 'Z');

async function main() {
  // ------------------------------------------------------------------ AUTH
  section = 'auth';
  const email = `ada+${rid()}@example.test`;
  expectStatus('register ok', await call('POST', '/auth/register', { body: { email, password: 'a long local test passphrase', displayName: 'Ada', timeZone: 'Asia/Kolkata' } }), 201);
  expectStatus('duplicate email', await call('POST', '/auth/register', { body: { email: email.toUpperCase(), password: 'a long local test passphrase', displayName: 'Ada', timeZone: 'Asia/Kolkata' } }), 409, 'EMAIL_ALREADY_REGISTERED');
  expectStatus('short password', await call('POST', '/auth/register', { body: { email: `b+${rid()}@example.test`, password: 'short', displayName: 'B', timeZone: 'UTC' } }), 400, 'VALIDATION_FAILED');
  expectStatus('common password', await call('POST', '/auth/register', { body: { email: `c+${rid()}@example.test`, password: 'password1234', displayName: 'C', timeZone: 'UTC' } }), 400, 'VALIDATION_FAILED');
  expectStatus('invalid time zone', await call('POST', '/auth/register', { body: { email: `d+${rid()}@example.test`, password: 'a long local test passphrase', displayName: 'D', timeZone: 'Mars/Base' } }), 400, 'VALIDATION_FAILED');
  expectStatus('invalid email', await call('POST', '/auth/register', { body: { email: 'not-an-email', password: 'a long local test passphrase', displayName: 'E', timeZone: 'UTC' } }), 400, 'VALIDATION_FAILED');
  expectStatus('malformed json', await call('POST', '/auth/register', { body: '{bad json' }), 400);
  expectStatus('wrong password', await call('POST', '/auth/login', { body: { email, password: 'wrong wrong wrong' } }), 401, 'INVALID_CREDENTIALS');
  expectStatus('unknown email', await call('POST', '/auth/login', { body: { email: `nobody+${rid()}@example.test`, password: 'whatever whatever' } }), 401, 'INVALID_CREDENTIALS');
  const web = await call('POST', '/auth/login', { body: { email, password: 'a long local test passphrase' } });
  expectStatus('login web', web, 200);
  check('web login sets HttpOnly SameSite=Strict cookie', /mp_refresh=.*HttpOnly.*SameSite=Strict/i.test(web.headers.get('set-cookie') || ''), web.headers.get('set-cookie'));
  check('web login hides refresh token in body', web.json && !web.json.refreshToken);
  const ada = await call('POST', '/auth/login', { body: { email, password: 'a long local test passphrase', clientType: 'NATIVE' } });
  expectStatus('me', await call('GET', '/users/me', { token: ada.json.accessToken }), 200);
  expectStatus('me without token', await call('GET', '/users/me'), 401, 'UNAUTHENTICATED');
  expectStatus('me garbage token', await call('GET', '/users/me', { token: 'abc.def.ghi' }), 401, 'TOKEN_INVALID');
  expectStatus('update profile', await call('PATCH', '/users/me', { token: ada.json.accessToken, body: { displayName: 'Ada L', timeZone: 'Europe/London' } }), 200);
  expectStatus('update profile bad avatar', await call('PATCH', '/users/me', { token: ada.json.accessToken, body: { avatarUrl: 'javascript:alert(1)' } }), 400);
  const r1 = await call('POST', '/auth/refresh', { body: { refreshToken: ada.json.refreshToken } });
  expectStatus('refresh native', r1, 200);
  expectStatus('refresh reuse rejected', await call('POST', '/auth/refresh', { body: { refreshToken: ada.json.refreshToken } }), 401, 'TOKEN_INVALID');
  expectStatus('refresh without token', await call('POST', '/auth/refresh'), 401, 'TOKEN_INVALID');
  expectStatus('sessions list', await call('GET', '/users/me/sessions', { token: r1.json.accessToken }), 200);
  expectStatus('change password wrong current', await call('POST', '/users/me/password', { token: r1.json.accessToken, body: { currentPassword: 'nope nope nope', newPassword: 'another long passphrase' } }), 400, 'VALIDATION_FAILED'); // not 401: the session is still valid
  expectStatus('change password ok', await call('POST', '/users/me/password', { token: r1.json.accessToken, body: { currentPassword: 'a long local test passphrase', newPassword: 'another long passphrase' } }), 204);
  expectStatus('old password fails', await call('POST', '/auth/login', { body: { email, password: 'a long local test passphrase' } }), 401);
  expectStatus('new password works', await call('POST', '/auth/login', { body: { email, password: 'another long passphrase' } }), 200);
  expectStatus('password reset request unknown (no enumeration)', await call('POST', '/auth/password-reset/request', { body: { email: `ghost+${rid()}@example.test` } }), 202);
  expectStatus('password reset bad token', await call('POST', '/auth/password-reset/confirm', { body: { token: 'nope', newPassword: 'another long passphrase' } }), 401, 'TOKEN_INVALID');
  expectStatus('verify email bad token', await call('POST', '/auth/verify-email', { body: { token: 'nope' } }), 401, 'TOKEN_INVALID');
  expectStatus('oauth providers', await call('GET', '/auth/oauth2/providers'), 200);
  const la = await call('POST', '/auth/login', { body: { email, password: 'another long passphrase', clientType: 'NATIVE' } });
  expectStatus('logout-all', await call('POST', '/auth/logout-all', { token: la.json.accessToken }), 204);
  await new Promise((r) => setTimeout(r, 11000)); // session status cache TTL
  expectStatus('token rejected after logout-all', await call('GET', '/users/me', { token: la.json.accessToken }), 401);

  // ------------------------------------------------------------------ MEETINGS
  section = 'meetings';
  const host = await user('Host');
  const p1 = await user('Pat');
  const p2 = await user('Quinn');
  const outsider = await user('Outsider');
  expectStatus('create missing title', await call('POST', '/meetings', { token: host.token, body: { type: 'INSTANT', timeZone: 'UTC' } }), 400, 'VALIDATION_FAILED');
  expectStatus('scheduled without start', await call('POST', '/meetings', { token: host.token, body: { title: 'x', type: 'SCHEDULED', timeZone: 'UTC' } }), 400, 'VALIDATION_FAILED');
  expectStatus('scheduled in past', await call('POST', '/meetings', { token: host.token, body: { title: 'x', type: 'SCHEDULED', timeZone: 'UTC', startTime: iso(Date.now() - 3600e3) } }), 400);
  expectStatus('personal room via create rejected', await call('POST', '/meetings', { token: host.token, body: { title: 'x', type: 'PERSONAL_ROOM', timeZone: 'UTC' } }), 400);
  expectStatus('bad rrule', await call('POST', '/meetings', { token: host.token, body: { title: 'x', type: 'SCHEDULED', timeZone: 'UTC', startTime: iso(Date.now() + 3600e3), recurrenceRule: 'FREQ=SECONDLY' } }), 400, 'INVALID_RECURRENCE');
  expectStatus('capacity over max', await call('POST', '/meetings', { token: host.token, body: { title: 'x', type: 'INSTANT', timeZone: 'UTC', maxParticipants: 1000 } }), 400);
  expectStatus('org privacy without org', await call('POST', '/meetings', { token: host.token, body: { title: 'x', type: 'INSTANT', timeZone: 'UTC', privacy: 'ORGANIZATION' } }), 400);
  const sched = await call('POST', '/meetings', { token: host.token, body: { title: 'Weekly sync', type: 'SCHEDULED', timeZone: 'Asia/Kolkata', startTime: iso(Date.now() + 86400e3), durationMinutes: 45, recurrenceRule: 'RRULE:FREQ=WEEKLY;BYDAY=MO', inviteeEmails: [p1.email, 'external@example.test', p1.email.toUpperCase()] } });
  expectStatus('create recurring scheduled', sched, 201);
  check('rrule normalized', sched.json?.recurrenceRule === 'FREQ=WEEKLY;BYDAY=MO', sched.json?.recurrenceRule);
  const inv = await call('GET', `/meetings/${sched.json.id}/invitations`, { token: host.token });
  check('duplicate invitee de-duplicated', inv.json?.length === 2, JSON.stringify(inv.json));
  expectStatus('invitee can view meeting', await call('GET', `/meetings/${sched.json.id}`, { token: p1.token }), 200);
  expectStatus('outsider cannot view meeting', await call('GET', `/meetings/${sched.json.id}`, { token: outsider.token }), 404);
  expectStatus('invitee cannot edit', await call('PATCH', `/meetings/${sched.json.id}`, { token: p1.token, body: { title: 'hacked' } }), 403);
  const upcoming = await call('GET', '/meetings?scope=upcoming', { token: p1.token });
  check('invitee sees meeting in upcoming', upcoming.json?.items?.some((m) => m.id === sched.json.id), JSON.stringify(upcoming.json).slice(0, 300));
  expectStatus('invalid scope', await call('GET', '/meetings?scope=bogus', { token: host.token }), 400);
  const upd = await call('PATCH', `/meetings/${sched.json.id}`, { token: host.token, body: { startTime: iso(Date.now() + 2 * 86400e3), version: sched.json.version } });
  expectStatus('reschedule', upd, 200);
  expectStatus('stale version conflict', await call('PATCH', `/meetings/${sched.json.id}`, { token: host.token, body: { title: 'x', version: sched.json.version } }), 409, 'OPTIMISTIC_LOCK_CONFLICT');
  expectStatus('add invitations', await call('POST', `/meetings/${sched.json.id}/invitations`, { token: host.token, body: { emails: [p2.email] } }), 201);
  expectStatus('cancel', await call('POST', `/meetings/${sched.json.id}/cancel`, { token: host.token, body: { reason: 'holiday' } }), 204);
  expectStatus('join cancelled', await call('POST', `/meetings/${sched.json.id}/join`, { token: p1.token }), 409, 'MEETING_CANCELLED');
  expectStatus('cancel twice', await call('POST', `/meetings/${sched.json.id}/cancel`, { token: host.token }), 409);
  expectStatus('edit cancelled', await call('PATCH', `/meetings/${sched.json.id}`, { token: host.token, body: { title: 'x' } }), 409);
  expectStatus('by-code unknown', await call('GET', '/meetings/by-code/zzzzzzzzzz'), 404);
  expectStatus('by-code invalid format', await call('GET', '/meetings/by-code/bad!code'), 400);
  expectStatus('get nonexistent', await call('GET', `/meetings/00000000-0000-0000-0000-000000000000`, { token: host.token }), 404);
  expectStatus('get bad uuid', await call('GET', `/meetings/not-a-uuid`, { token: host.token }), 400);
  const pr = await call('GET', '/rooms/personal', { token: host.token });
  expectStatus('personal room', pr, 200);
  const pr2 = await call('GET', '/rooms/personal', { token: host.token });
  check('personal room stable', pr.json?.id === pr2.json?.id);
  expectStatus('personal room cannot be scheduled', await call('PATCH', '/rooms/personal', { token: host.token, body: { startTime: iso(Date.now() + 3600e3) } }), 400);

  // ------------------------------------------------------------------ JOIN / LIVE
  section = 'join';
  const live = await call('POST', '/meetings', { token: host.token, body: { title: 'Live', type: 'INSTANT', timeZone: 'UTC', waitingRoomEnabled: true, password: 'open sesame', maxParticipants: 4 } });
  const mid = live.json.id;
  const byCode = await call('GET', `/meetings/by-code/${live.json.joinCode.toUpperCase()}`);
  expectStatus('by-code case insensitive', byCode, 200);
  check('by-code says password required', byCode.json?.passwordRequired === true);
  expectStatus('guest joins before host (joinBeforeHost default true, waiting room)', await call('POST', `/meetings/${mid}/join`, { body: { displayName: 'Early', password: 'open sesame' } }), 200);
  const hj = await call('POST', `/meetings/${mid}/join`, { token: host.token });
  expectStatus('host joins without password', hj, 200);
  check('host admitted with token', hj.json?.status === 'ADMITTED' && hj.json?.token, JSON.stringify(hj.json).slice(0, 200));
  expectStatus('guest without name', await call('POST', `/meetings/${mid}/join`, { body: { password: 'open sesame' } }), 400);
  expectStatus('missing password', await call('POST', `/meetings/${mid}/join`, { body: { displayName: 'G' } }), 422, 'MEETING_PASSWORD_REQUIRED');
  expectStatus('wrong password', await call('POST', `/meetings/${mid}/join`, { body: { displayName: 'G', password: 'nope' } }), 403, 'MEETING_PASSWORD_INVALID');
  const g = await call('POST', `/meetings/${mid}/join`, { body: { displayName: 'Guest Gina', password: 'open sesame' } });
  check('guest waits', g.json?.status === 'WAITING' && !g.json?.token, JSON.stringify(g.json).slice(0, 200));
  const pj = await call('POST', `/meetings/${mid}/join`, { token: p1.token, body: { password: 'open sesame' } });
  check('participant waits', pj.json?.status === 'WAITING');
  const pjAgain = await call('POST', `/meetings/${mid}/join`, { token: p1.token, body: { password: 'open sesame' } });
  check('rejoin reuses participant', pjAgain.json?.participantId === pj.json?.participantId, `${pjAgain.json?.participantId} vs ${pj.json?.participantId}`);
  const waiting = await call('GET', `/meetings/${mid}/moderation/waiting`, { token: host.token });
  check('waiting list has 3', waiting.json?.length === 3, JSON.stringify(waiting.json?.map((w) => w.displayName)));
  expectStatus('participant cannot see waiting list', await call('GET', `/meetings/${mid}/moderation/waiting`, { token: p1.token }), 403);
  expectStatus('admit guest', await call('POST', `/meetings/${mid}/moderation/waiting/${g.json.participantId}/admit`, { token: host.token }), 204);
  expectStatus('admit again conflict', await call('POST', `/meetings/${mid}/moderation/waiting/${g.json.participantId}/admit`, { token: host.token }), 409);
  const gs = await call('GET', `/meetings/${mid}/join-status?participantId=${g.json.participantId}`, { headers: { 'X-Participant-Token': g.json.waitToken } });
  check('guest admitted via status', gs.json?.status === 'ADMITTED' && gs.json?.token, JSON.stringify(gs.json).slice(0, 200));
  expectStatus('status with query waitToken', await call('GET', `/meetings/${mid}/join-status?participantId=${g.json.participantId}&waitToken=${encodeURIComponent(g.json.waitToken)}`), 200);
  expectStatus('deny participant', await call('POST', `/meetings/${mid}/moderation/waiting/${pj.json.participantId}/deny`, { token: host.token }), 204);
  const ds = await call('GET', `/meetings/${mid}/join-status?participantId=${pj.json.participantId}`, { token: p1.token });
  check('denied status', ds.json?.status === 'DENIED', JSON.stringify(ds.json).slice(0, 200));
  const early = waiting.json.find((w) => w.displayName === 'Early');
  expectStatus('admit-all', await call('POST', `/meetings/${mid}/moderation/waiting/admit-all`, { token: host.token }), 204);
  // capacity 4: host + gina + early admitted = 3; one more ok, then full
  const g4 = await call('POST', `/meetings/${mid}/join`, { body: { displayName: 'Fourth', password: 'open sesame' } });
  expectStatus('admit fourth', await call('POST', `/meetings/${mid}/moderation/waiting/${g4.json.participantId}/admit`, { token: host.token }), 204);
  expectStatus('fifth join refused when full', await call('POST', `/meetings/${mid}/join`, { body: { displayName: 'Fifth', password: 'open sesame' } }), 409, 'MEETING_FULL');
  const g5 = { json: pj.json };

  section = 'moderation';
  expectStatus('chat send by guest', await call('POST', `/meetings/${mid}/chat`, { headers: { 'X-Participant-Token': g.json.waitToken }, body: { participantId: g.json.participantId, text: 'hello <b>world</b>' } }), 201);
  expectStatus('chat send with wrong token', await call('POST', `/meetings/${mid}/chat`, { headers: { 'X-Participant-Token': 'bad' }, body: { participantId: g.json.participantId, text: 'x' } }), 403);
  expectStatus('chat send blank', await call('POST', `/meetings/${mid}/chat`, { headers: { 'X-Participant-Token': g.json.waitToken }, body: { participantId: g.json.participantId, text: '   ' } }), 400);
  expectStatus('chat by denied participant refused', await call('POST', `/meetings/${mid}/chat`, { headers: { 'X-Participant-Token': g5.json.waitToken }, body: { participantId: g5.json.participantId, text: 'x' } }), 403);
  const hist = await call('GET', `/meetings/${mid}/chat`, { headers: { 'X-Participant-Token': g.json.waitToken } });
  check('guest reads history by token only', hist.status === 200 && hist.json?.length === 1, `${hist.status} ${hist.text.slice(0, 200)}`);
  expectStatus('outsider cannot read history', await call('GET', `/meetings/${mid}/chat`, { token: outsider.token }), 404);
  expectStatus('anonymous cannot read history', await call('GET', `/meetings/${mid}/chat`), 403);
  expectStatus('disable chat', await call('PUT', `/meetings/${mid}/moderation/settings`, { token: host.token, body: { chatEnabled: false, screenSharePolicy: 'HOSTS_ONLY' } }), 200);
  expectStatus('chat disabled for guest', await call('POST', `/meetings/${mid}/chat`, { headers: { 'X-Participant-Token': g.json.waitToken }, body: { participantId: g.json.participantId, text: 'x' } }), 403);
  const meetingNow = await call('GET', `/meetings/${mid}`, { token: host.token });
  check('meeting reflects chatEnabled=false', meetingNow.json?.chatEnabled === false, JSON.stringify(meetingNow.json).slice(0, 300));
  expectStatus('mute not-yet-connected participant', await call('POST', `/meetings/${mid}/moderation/participants/${g.json.participantId}/mute`, { token: host.token, body: { source: 'MICROSOFT' } }), 400);
  const muteRes = await call('POST', `/meetings/${mid}/moderation/participants/${g.json.participantId}/mute`, { token: host.token, body: { source: 'MICROPHONE' } });
  check('mute participant not connected to SFU handled (not 500)', muteRes.status !== 500, `${muteRes.status} ${muteRes.text.slice(0, 200)}`);
  expectStatus('mute-all', await call('POST', `/meetings/${mid}/moderation/mute-all`, { token: host.token }), 204);
  expectStatus('request-unmute', await call('POST', `/meetings/${mid}/moderation/participants/${g.json.participantId}/request-unmute`, { token: host.token, body: { source: 'MICROPHONE' } }), 204);
  expectStatus('promote guest refused', await call('POST', `/meetings/${mid}/moderation/participants/${g.json.participantId}/role`, { token: host.token, body: { role: 'CO_HOST' } }), 409);
  expectStatus('promote to HOST refused', await call('POST', `/meetings/${mid}/moderation/participants/${g.json.participantId}/role`, { token: host.token, body: { role: 'HOST' } }), 400);
  expectStatus('lock', await call('POST', `/meetings/${mid}/moderation/lock`, { token: host.token, body: { locked: true } }), 204);
  expectStatus('locked join refused', await call('POST', `/meetings/${mid}/join`, { token: p2.token, body: { password: 'open sesame' } }), 403, 'MEETING_LOCKED');
  expectStatus('unlock', await call('POST', `/meetings/${mid}/moderation/lock`, { token: host.token, body: { locked: false } }), 204);
  expectStatus('remove guest', await call('POST', `/meetings/${mid}/moderation/participants/${g.json.participantId}/remove`, { token: host.token }), 204);
  const removedStatus = await call('GET', `/meetings/${mid}/join-status?participantId=${g.json.participantId}`, { headers: { 'X-Participant-Token': g.json.waitToken } });
  check('removed guest gets DENIED', removedStatus.json?.status === 'DENIED', JSON.stringify(removedStatus.json).slice(0, 200));
  expectStatus('remove host refused', await call('POST', `/meetings/${mid}/moderation/participants/${hj.json.participantId}/remove`, { token: host.token }), 403);
  expectStatus('local recording notice', await call('POST', `/meetings/${mid}/local-recordings`, { token: host.token, body: { participantId: hj.json.participantId, state: 'STARTED' } }), 204);
  const rec = await call('POST', `/meetings/${mid}/recordings`, { token: host.token });
  check('cloud recording without egress handled (503, not 500)', rec.status === 503, `${rec.status} ${rec.text.slice(0, 200)}`);
  const recs = await call('GET', `/meetings/${mid}/recordings`, { token: host.token });
  check('failed recording is listed as FAILED', recs.json?.[0]?.status === 'FAILED', JSON.stringify(recs.json).slice(0, 200));
  const audit = await call('GET', `/meetings/${mid}/moderation/audit`, { token: host.token });
  check('audit trail populated', (audit.json?.items?.length ?? 0) >= 8, `${audit.json?.items?.length}`);
  const fd = new FormData();
  fd.append('file', new Blob(['hello'], { type: 'text/plain' }), 'notes.txt');
  fd.append('participantId', hj.json.participantId);
  const up = await call('POST', `/meetings/${mid}/files`, { token: host.token, body: fd });
  check('file upload without storage returns 503', up.status === 503, `${up.status} ${up.text.slice(0, 200)}`);
  const fd2 = new FormData();
  fd2.append('file', new Blob(['MZ'], { type: 'application/x-msdownload' }), 'evil.exe');
  fd2.append('participantId', hj.json.participantId);
  expectStatus('executable rejected', await call('POST', `/meetings/${mid}/files`, { token: host.token, body: fd2 }), 415, 'FILE_TYPE_NOT_ALLOWED');
  const fd3 = new FormData();
  fd3.append('file', new Blob(['x'], { type: 'application/pdf' }), 'fake.exe');
  fd3.append('participantId', hj.json.participantId);
  expectStatus('extension mismatch rejected', await call('POST', `/meetings/${mid}/files`, { token: host.token, body: fd3 }), 415);
  expectStatus('participant cannot end', await call('POST', `/meetings/${mid}/end`, { token: p1.token }), 403);
  expectStatus('leave host', await call('POST', `/meetings/${mid}/leave`, { token: host.token, body: { participantId: hj.json.participantId } }), 204);
  expectStatus('end by host', await call('POST', `/meetings/${mid}/end`, { token: host.token }), 204);
  expectStatus('end twice', await call('POST', `/meetings/${mid}/end`, { token: host.token }), 409, 'MEETING_NOT_ACTIVE');
  const parts = await call('GET', `/meetings/${mid}/participants`, { token: host.token });
  check('no open participants after end', parts.json?.every((p) => !['WAITING', 'ADMITTED', 'JOINED'].includes(p.state)), JSON.stringify(parts.json?.map((p) => p.state)));
  const ended = await call('GET', `/meetings/${mid}`, { token: host.token });
  check('meeting ENDED', ended.json?.status === 'ENDED', ended.json?.status);
  const past = await call('GET', '/meetings?scope=past', { token: host.token });
  check('ended meeting in past list', past.json?.items?.some((m) => m.id === mid));

  // join-before-host disabled
  const jbh = await call('POST', '/meetings', { token: host.token, body: { title: 'Wait for host', type: 'INSTANT', timeZone: 'UTC', joinBeforeHost: false } });
  const early2 = await call('POST', `/meetings/${jbh.json.id}/join`, { body: { displayName: 'Too early' } });
  check('waits for host', early2.json?.status === 'WAITING' && early2.json?.waitingReason === 'WAITING_FOR_HOST', JSON.stringify(early2.json).slice(0, 200));
  await call('POST', `/meetings/${jbh.json.id}/join`, { token: host.token });
  const after = await call('GET', `/meetings/${jbh.json.id}/join-status?participantId=${early2.json.participantId}`, { headers: { 'X-Participant-Token': early2.json.waitToken } });
  check('auto-admitted when host arrives', after.json?.status === 'ADMITTED', JSON.stringify(after.json).slice(0, 200));
  await call('POST', `/meetings/${jbh.json.id}/end`, { token: host.token });

  // personal room is reusable
  const prJoin = await call('POST', `/meetings/${pr.json.id}/join`, { token: host.token });
  check('personal room starts', prJoin.json?.status === 'ADMITTED');
  await call('POST', `/meetings/${pr.json.id}/end`, { token: host.token });
  const prAgain = await call('POST', `/meetings/${pr.json.id}/join`, { token: host.token });
  check('personal room reusable after end', prAgain.json?.status === 'ADMITTED', JSON.stringify(prAgain.json).slice(0, 200));
  await call('POST', `/meetings/${pr.json.id}/end`, { token: host.token });

  // invitees-only and guests-not-allowed
  const priv = await call('POST', '/meetings', { token: host.token, body: { title: 'Private', type: 'INSTANT', timeZone: 'UTC', privacy: 'INVITEES_ONLY', inviteeEmails: [p1.email] } });
  expectStatus('guest refused on private', await call('POST', `/meetings/${priv.json.id}/join`, { body: { displayName: 'Anon' } }), 403, 'GUESTS_NOT_ALLOWED');
  expectStatus('outsider refused on private', await call('POST', `/meetings/${priv.json.id}/join`, { token: outsider.token }), 403, 'NOT_INVITED');
  expectStatus('invitee allowed on private', await call('POST', `/meetings/${priv.json.id}/join`, { token: p1.token }), 200);

  // ------------------------------------------------------------------ ORGANIZATIONS
  section = 'organizations';
  const slug = `acme-${rid()}`;
  const org = await call('POST', '/organizations', { token: host.token, body: { name: 'Acme', slug } });
  expectStatus('create org', org, 201);
  expectStatus('duplicate slug', await call('POST', '/organizations', { token: p1.token, body: { name: 'Acme 2', slug } }), 409, 'SLUG_TAKEN');
  expectStatus('bad slug', await call('POST', '/organizations', { token: p1.token, body: { name: 'X', slug: 'Bad Slug!' } }), 400);
  expectStatus('add member', await call('POST', `/organizations/${org.json.id}/members`, { token: host.token, body: { email: p1.email, role: 'MEMBER' } }), 201);
  expectStatus('add unknown member', await call('POST', `/organizations/${org.json.id}/members`, { token: host.token, body: { email: `nobody+${rid()}@example.test`, role: 'MEMBER' } }), 404);
  expectStatus('member cannot add', await call('POST', `/organizations/${org.json.id}/members`, { token: p1.token, body: { email: p2.email, role: 'MEMBER' } }), 403);
  expectStatus('outsider cannot see org', await call('GET', `/organizations/${org.json.id}`, { token: outsider.token }), 404);
  expectStatus('last admin cannot leave', await call('DELETE', `/organizations/${org.json.id}/members/${host.id}`, { token: host.token }), 409);
  expectStatus('org meeting with org privacy', await call('POST', '/meetings', { token: host.token, body: { title: 'All hands', type: 'INSTANT', timeZone: 'UTC', privacy: 'ORGANIZATION', organizationId: org.json.id } }), 201);
  expectStatus('non-member cannot use org', await call('POST', '/meetings', { token: outsider.token, body: { title: 'x', type: 'INSTANT', timeZone: 'UTC', organizationId: org.json.id } }), 403);
  expectStatus('update org', await call('PATCH', `/organizations/${org.json.id}`, { token: host.token, body: { allowGuests: false, defaultRecordingRetentionDays: 30 } }), 200);

  // ------------------------------------------------------------------ SCHEDULING
  section = 'scheduling';
  const rules = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].map((d) => ({ dayOfWeek: d, startTime: '09:00', endTime: '17:00' }));
  expectStatus('overlapping rules rejected', await call('PUT', '/availability', { token: p2.token, body: { timeZone: 'America/New_York', weeklyRules: [{ dayOfWeek: 'MONDAY', startTime: '09:00', endTime: '12:00' }, { dayOfWeek: 'MONDAY', startTime: '11:00', endTime: '13:00' }] } }), 400);
  expectStatus('end before start rejected', await call('PUT', '/availability', { token: p2.token, body: { timeZone: 'America/New_York', weeklyRules: [{ dayOfWeek: 'MONDAY', startTime: '12:00', endTime: '09:00' }] } }), 400);
  const av = await call('PUT', '/availability', { token: p2.token, body: { timeZone: 'America/New_York', weeklyRules: rules } });
  expectStatus('set availability', av, 200);
  check('availability round trip', av.json?.weeklyRules?.length === 7 && av.json.timeZone === 'America/New_York', JSON.stringify(av.json).slice(0, 200));
  const day3 = new Date(Date.now() + 3 * 86400e3).toISOString().slice(0, 10);
  const day4 = new Date(Date.now() + 4 * 86400e3).toISOString().slice(0, 10);
  expectStatus('exception day off', await call('POST', '/availability/exceptions', { token: p2.token, body: { date: day4, available: false, reason: 'vacation' } }), 201);
  expectStatus('exception available without times rejected', await call('POST', '/availability/exceptions', { token: p2.token, body: { date: day4, available: true } }), 400);
  const bslug = `chat-${rid()}`;
  const bp = await call('POST', '/booking-pages', { token: p2.token, body: { slug: bslug, title: '30 min chat', durationMinutes: 30, minNoticeMinutes: 60, maxAdvanceDays: 30, bufferAfterMinutes: 15 } });
  expectStatus('create booking page', bp, 201);
  expectStatus('duplicate booking slug', await call('POST', '/booking-pages', { token: p1.token, body: { slug: bslug, title: 'x', durationMinutes: 30 } }), 409, 'SLUG_TAKEN');
  expectStatus('public page', await call('GET', `/booking-pages/public/${bslug}`), 200);
  expectStatus('public page unknown', await call('GET', `/booking-pages/public/nope-${rid()}`), 404);
  expectStatus('slots invalid zone', await call('GET', `/booking-pages/public/${bslug}/slots?from=${day3}&to=${day3}&timeZone=Bad/Zone`), 400);
  expectStatus('slots range too long', await call('GET', `/booking-pages/public/${bslug}/slots?from=2026-01-01&to=2026-12-31&timeZone=UTC`), 400);
  expectStatus('slots to before from', await call('GET', `/booking-pages/public/${bslug}/slots?from=${day4}&to=${day3}&timeZone=UTC`), 400);
  const slots = await call('GET', `/booking-pages/public/${bslug}/slots?from=${day3}&to=${day3}&timeZone=America/New_York`);
  check('slots returned (09:00-17:00 in 30 min steps => 16)', slots.json?.slots?.length === 16, `${slots.json?.slots?.length} ${slots.text.slice(0, 200)}`);
  const offDay = await call('GET', `/booking-pages/public/${bslug}/slots?from=${day4}&to=${day4}&timeZone=America/New_York`);
  check('no slots on day off', offDay.json?.slots?.length === 0, `${offDay.json?.slots?.length}`);
  const slot = slots.json.slots[2].start;
  expectStatus('book unoffered time', await call('POST', '/bookings', { body: { slug: bslug, startTime: slot.replace(':00:00Z', ':07:00Z').replace(':30:00Z', ':07:00Z'), inviteeName: 'Ivy', inviteeEmail: 'ivy@example.test', inviteeTimeZone: 'Asia/Kolkata' } }), 409, 'SLOT_UNAVAILABLE');
  const key = `key-${rid()}-${rid()}`;
  const b1 = await call('POST', '/bookings', { headers: { 'Idempotency-Key': key }, body: { slug: bslug, startTime: slot, inviteeName: 'Ivy', inviteeEmail: 'ivy@example.test', inviteeTimeZone: 'Asia/Kolkata' } });
  expectStatus('book', b1, 201);
  const b1replay = await call('POST', '/bookings', { headers: { 'Idempotency-Key': key }, body: { slug: bslug, startTime: slot, inviteeName: 'Ivy', inviteeEmail: 'ivy@example.test', inviteeTimeZone: 'Asia/Kolkata' } });
  check('idempotent replay returns same booking', b1replay.status === 201 && b1replay.json?.id === b1.json?.id, `${b1replay.status} ${b1replay.text.slice(0, 200)}`);
  expectStatus('idempotency key reused with different body', await call('POST', '/bookings', { headers: { 'Idempotency-Key': key }, body: { slug: bslug, startTime: slot, inviteeName: 'Other', inviteeEmail: 'o@example.test', inviteeTimeZone: 'UTC' } }), 409);
  expectStatus('double booking', await call('POST', '/bookings', { body: { slug: bslug, startTime: slot, inviteeName: 'Jay', inviteeEmail: 'jay@example.test', inviteeTimeZone: 'UTC' } }), 409, 'SLOT_UNAVAILABLE');
  const nextSlot = slots.json.slots[3].start; // within 15 min after-buffer? slots are 30 min apart, buffer 15 → overlapping blocked
  const after1 = await call('GET', `/booking-pages/public/${bslug}/slots?from=${day3}&to=${day3}&timeZone=America/New_York`);
  check('booked slot and buffer-blocked neighbour removed', !after1.json.slots.some((s) => s.start === slot) && !after1.json.slots.some((s) => s.start === nextSlot), JSON.stringify(after1.json.slots.map((s) => s.start)));
  const hostBookings = await call('GET', '/bookings?scope=upcoming', { token: p2.token });
  check('host sees booking', hostBookings.json?.items?.some((b) => b.id === b1.json.id));
  expectStatus('other user cannot see booking', await call('GET', `/bookings/${b1.json.id}`, { token: p1.token }), 404);
  const manage = await call('GET', `/bookings/manage/${b1.json.manageToken}`);
  check('manage view hides token', manage.status === 200 && !manage.json?.manageToken);
  const meetingOfBooking = await call('GET', `/meetings/${b1.json.meetingId}`, { token: p2.token });
  check('booking created meeting for host', meetingOfBooking.status === 200 && meetingOfBooking.json.status === 'SCHEDULED', meetingOfBooking.text.slice(0, 200));
  const resch = await call('POST', `/bookings/manage/${b1.json.manageToken}/reschedule`, { body: { startTime: slots.json.slots[6].start } });
  expectStatus('reschedule', resch, 200);
  const oldMeeting = await call('GET', `/meetings/${b1.json.meetingId}`, { token: p2.token });
  check('old booking meeting cancelled', oldMeeting.json?.status === 'CANCELLED', oldMeeting.json?.status);
  expectStatus('reschedule old token refused', await call('POST', `/bookings/manage/${b1.json.manageToken}/reschedule`, { body: { startTime: slots.json.slots[8].start } }), 409, 'BOOKING_NOT_MODIFIABLE');
  const reslots = await call('GET', `/booking-pages/public/${bslug}/slots?from=${day3}&to=${day3}&timeZone=America/New_York`);
  check('original slot freed after reschedule', reslots.json.slots.some((s) => s.start === slot), JSON.stringify(reslots.json.slots.map((s) => s.start)));
  expectStatus('cancel by host', await call('POST', `/bookings/${resch.json.id}/cancel`, { token: p2.token, body: { reason: 'sick' } }), 204);
  expectStatus('cancel twice', await call('POST', `/bookings/manage/${resch.json.manageToken}/cancel`), 409);
  expectStatus('inactive page hidden', await call('PATCH', `/booking-pages/${bp.json.id}`, { token: p2.token, body: { active: false } }), 200);
  expectStatus('inactive page 404', await call('GET', `/booking-pages/public/${bslug}`), 404);
  expectStatus('other user cannot edit page', await call('PATCH', `/booking-pages/${bp.json.id}`, { token: p1.token, body: { title: 'x' } }), 404);

  // ------------------------------------------------------------------ NOTIFICATIONS / ADMIN / MISC
  section = 'notifications';
  const notes = await call('GET', '/notifications', { token: p2.token });
  check('host got booking notifications', (notes.json?.items?.length ?? 0) >= 2, `${notes.json?.items?.length}`);
  expectStatus('read-all', await call('POST', '/notifications/read-all', { token: p2.token }), 204);
  const unread = await call('GET', '/notifications?unreadOnly=true', { token: p2.token });
  check('no unread after read-all', unread.json?.items?.length === 0, `${unread.json?.items?.length}`);
  const p1Notes = await call('GET', '/notifications', { token: p1.token });
  check('invitee got invitation notification', p1Notes.json?.items?.some((n) => n.type === 'MEETING_INVITATION'), JSON.stringify(p1Notes.json?.items?.map((n) => n.type)));
  expectStatus('cannot read other user notification', await call('POST', `/notifications/${notes.json.items[0].id}/read`, { token: p1.token }), 404);

  section = 'admin';
  expectStatus('non-admin forbidden', await call('GET', '/admin/stats', { token: host.token }), 403);
  expectStatus('admin anonymous', await call('GET', '/admin/stats'), 401);

  section = 'recordings';
  expectStatus('library', await call('GET', '/recordings', { token: host.token }), 200);
  expectStatus('recording unknown', await call('GET', '/recordings/00000000-0000-0000-0000-000000000000', { token: host.token }), 404);

  section = 'calendar';
  const cal = await call('GET', '/calendar/connections', { token: host.token });
  check('calendar list shows unavailable providers', cal.status === 200 && cal.json?.every((c) => c.available === false), cal.text.slice(0, 200));
  expectStatus('calendar authorize not configured', await call('POST', '/calendar/connections/google/authorize', { token: host.token }), 404);
  const cb = await call('GET', '/calendar/oauth/callback?code=x&state=forged.state');
  check('calendar callback with forged state redirects to error', cb.status === 302 && /calendar=error/.test(cb.headers.get('location') || ''), `${cb.status} ${cb.headers.get('location')}`);

  section = 'webhooks';
  expectStatus('webhook unsigned', await call('POST', '/webhooks/livekit', { body: '{}', headers: { 'Content-Type': 'application/webhook+json' } }), 401, 'WEBHOOK_SIGNATURE_INVALID');
  expectStatus('webhook forged', await call('POST', '/webhooks/livekit', { body: '{}', headers: { 'Content-Type': 'application/webhook+json', Authorization: 'eyJhbGciOiJIUzI1NiJ9.e30.x' } }), 401);

  section = 'misc';
  expectStatus('unknown route 404 problem json', await call('GET', '/does-not-exist', { token: host.token }), 404);
  expectStatus('method not allowed', await call('DELETE', '/auth/login'), 405);
  const cors = await fetch(BASE + '/meetings', { method: 'OPTIONS', headers: { Origin: 'https://evil.example', 'Access-Control-Request-Method': 'POST' } });
  check('CORS rejects unknown origin', !cors.headers.get('access-control-allow-origin'), cors.headers.get('access-control-allow-origin'));
  const cors2 = await fetch(BASE + '/meetings', { method: 'OPTIONS', headers: { Origin: 'http://localhost:5173', 'Access-Control-Request-Method': 'POST' } });
  check('CORS allows web origin', cors2.headers.get('access-control-allow-origin') === 'http://localhost:5173');
  const health = await fetch(BASE.replace('/api/v1', '') + '/actuator/health/readiness');
  check('health UP', health.status === 200);
  const docs = await fetch(BASE.replace('/api/v1', '') + '/v3/api-docs');
  check('openapi docs', docs.status === 200);
  const big = await call('GET', '/meetings?scope=all&size=100000', { token: host.token });
  check('page size clamped', big.json?.size === 100, `${big.json?.size}`);

  // ------------------------------------------------------------------ report
  const failed = results.filter((r) => !r.ok);
  for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'}  [${r.section}] ${r.name}${r.ok ? '' : '\n      ' + r.detail}`);
  console.log(`\n${results.length - failed.length}/${results.length} passed, ${failed.length} failed`);
  return failed.length;
}

main().then((failed) => process.exit(failed ? 1 : 0)).catch((e) => { console.error('FATAL', e); for (const r of results.filter((x) => !x.ok)) console.log('FAIL', r.section, r.name, r.detail); process.exit(1); });
