// Race-condition suite: many simultaneous requests against invariants that must hold.
// Usage: node qa/concurrency.mjs
import { call, newUser, rid, iso, pool, Report } from './lib.mjs';

const R = new Report('Concurrency suite');

async function main() {
  const host = await newUser('Race Host');

  // ------------------------------------------------------------------ capacity
  R.section = 'capacity';
  const m = (await call('POST', '/meetings', { token: host.token, body: { title: 'Capacity race', type: 'INSTANT', timeZone: 'UTC', maxParticipants: 10 } })).json;
  await call('POST', `/meetings/${m.id}/join`, { token: host.token });
  const joins = await Promise.all(Array.from({ length: 50 }, (_, i) => call('POST', `/meetings/${m.id}/join`, { body: { displayName: `Racer ${i}` } })));
  const admitted = joins.filter((r) => r.json?.status === 'ADMITTED').length;
  const full = joins.filter((r) => r.json?.code === 'MEETING_FULL').length;
  R.check('50 simultaneous joins into 10 seats admit exactly 9 (+host)', admitted === 9, `admitted=${admitted} full=${full} others=${joins.filter((r) => r.status !== 200 && r.json?.code !== 'MEETING_FULL').map((r) => r.status).join(',')}`);
  R.check('every refused join is MEETING_FULL, none 5xx', admitted + full === 50, `admitted=${admitted} full=${full}`);
  const parts = (await call('GET', `/meetings/${m.id}/participants`, { token: host.token })).json;
  R.check('participant rows match admitted count', parts.filter((p) => p.state === 'ADMITTED').length === 10, `${parts.length}`);

  // waiting room: admit-all vs individual admits racing against capacity
  const w = (await call('POST', '/meetings', { token: host.token, body: { title: 'Admit race', type: 'INSTANT', timeZone: 'UTC', maxParticipants: 5, waitingRoomEnabled: true } })).json;
  await call('POST', `/meetings/${w.id}/join`, { token: host.token });
  const waiters = await Promise.all(Array.from({ length: 12 }, (_, i) => call('POST', `/meetings/${w.id}/join`, { body: { displayName: `Waiter ${i}` } })));
  R.check('12 waiters queued (waiting does not consume seats)', waiters.every((r) => r.json?.status === 'WAITING'), waiters.map((r) => r.status).join(','));
  const admits = await Promise.all(waiters.map((r) => call('POST', `/meetings/${w.id}/moderation/waiting/${r.json.participantId}/admit`, { token: host.token })));
  const ok = admits.filter((r) => r.status === 204).length;
  R.check('12 concurrent admits into 4 free seats: exactly 4 succeed', ok === 4, `ok=${ok} codes=${admits.map((r) => r.json?.code ?? r.status).join(',')}`);

  // ------------------------------------------------------------------ room start race
  R.section = 'room start';
  const s = (await call('POST', '/meetings', { token: host.token, body: { title: 'Start race', type: 'INSTANT', timeZone: 'UTC' } })).json;
  const starters = await Promise.all(Array.from({ length: 20 }, (_, i) => i === 0
    ? call('POST', `/meetings/${s.id}/join`, { token: host.token })
    : call('POST', `/meetings/${s.id}/join`, { body: { displayName: `Starter ${i}` } })));
  const rooms = new Set(starters.filter((r) => r.json?.roomName).map((r) => r.json.roomName));
  R.check('20 simultaneous first joins create exactly one room', rooms.size === 1, [...rooms].join(','));
  R.check('no 5xx during room start race', starters.every((r) => r.status < 500), starters.map((r) => r.status).join(','));
  const ends = await Promise.all(Array.from({ length: 5 }, () => call('POST', `/meetings/${s.id}/end`, { token: host.token })));
  R.check('5 simultaneous end calls: one 204, rest 409', ends.filter((r) => r.status === 204).length === 1 && ends.filter((r) => r.status === 409).length === 4, ends.map((r) => r.status).join(','));

  // ------------------------------------------------------------------ refresh token race
  R.section = 'refresh';
  const u = await newUser('Refresh Race');
  const refreshes = await Promise.all(Array.from({ length: 20 }, () => call('POST', '/auth/refresh', { body: { refreshToken: u.refresh } })));
  R.check('20 parallel uses of one refresh token: exactly 1 succeeds', refreshes.filter((r) => r.status === 200).length === 1, refreshes.map((r) => r.status).join(','));
  R.check('losers get 401, never 5xx', refreshes.filter((r) => r.status !== 200).every((r) => r.status === 401), refreshes.map((r) => r.status).join(','));

  // ------------------------------------------------------------------ bookings
  R.section = 'booking';
  const owner = await newUser('Booking Owner');
  await call('PUT', '/availability', { token: owner.token, body: { timeZone: 'UTC', weeklyRules: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].map((d) => ({ dayOfWeek: d, startTime: '08:00', endTime: '18:00' })) } });
  const slug = `race-${rid()}`;
  await call('POST', '/booking-pages', { token: owner.token, body: { slug, title: 'Race', durationMinutes: 30, minNoticeMinutes: 0, bufferAfterMinutes: 10 } });
  const day = new Date(Date.now() + 5 * 86400e3).toISOString().slice(0, 10);
  const slots = (await call('GET', `/booking-pages/public/${slug}/slots?from=${day}&to=${day}&timeZone=UTC`)).json.slots;
  const targets = slots.slice(0, 10);
  // 20 bookers per slot, all at once (200 requests)
  const attempts = targets.flatMap((s, si) => Array.from({ length: 20 }, (_, i) => ({ s, si, i })));
  const results = await pool(attempts, 100, (a) => call('POST', '/bookings', { body: { slug, startTime: a.s.start, inviteeName: `B${a.si}-${a.i}`, inviteeEmail: `b${a.si}-${a.i}@example.test`, inviteeTimeZone: 'UTC' } }));
  const won = results.filter((r) => r.status === 201);
  // Slots are 30 min apart with a 10 min after-buffer: each booking blocks its own slot and the next one,
  // so the number of winners is between 5 and 10 but no two confirmed bookings may overlap.
  R.check('no 5xx under booking contention', results.every((r) => r.status < 500), [...new Set(results.map((r) => r.status))].join(','));
  R.check('losers get SLOT_UNAVAILABLE', results.filter((r) => r.status !== 201).every((r) => r.json?.code === 'SLOT_UNAVAILABLE'), [...new Set(results.filter((r) => r.status !== 201).map((r) => r.json?.code))].join(','));
  const confirmed = (await call('GET', '/bookings?scope=upcoming&size=100', { token: owner.token })).json.items.filter((b) => b.status === 'CONFIRMED');
  const sorted = confirmed.map((b) => [Date.parse(b.startTime), Date.parse(b.endTime) + 10 * 60e3]).sort((a, b) => a[0] - b[0]);
  const overlaps = sorted.some((b, i) => i > 0 && b[0] < sorted[i - 1][1]);
  R.check(`confirmed bookings (${confirmed.length}) never overlap incl. buffer`, !overlaps && confirmed.length === won.length && won.length >= 5, `won=${won.length} confirmed=${confirmed.length} overlaps=${overlaps}`);

  // idempotency: 20 identical requests with the same key at once
  const key = `idem-${rid()}-${rid()}`;
  const slot2 = slots[slots.length - 1].start;
  const idem = await Promise.all(Array.from({ length: 20 }, () => call('POST', '/bookings', { headers: { 'Idempotency-Key': key }, body: { slug, startTime: slot2, inviteeName: 'Idem', inviteeEmail: 'idem@example.test', inviteeTimeZone: 'UTC' } })));
  const ids = new Set(idem.filter((r) => r.status === 201).map((r) => r.json.id));
  R.check('20 parallel retries with one Idempotency-Key create at most one booking', ids.size === 1, `distinct ids=${ids.size} statuses=${idem.map((r) => r.status).join(',')}`);

  // ------------------------------------------------------------------ registration & org races
  R.section = 'identity';
  const email = `dup+${rid()}@example.test`;
  const regs = await Promise.all(Array.from({ length: 15 }, () => call('POST', '/auth/register', { body: { email, password: 'a long local test passphrase', displayName: 'Dup', timeZone: 'UTC' } })));
  R.check('15 parallel registrations of one e-mail: exactly 1 account', regs.filter((r) => r.status === 201).length === 1, regs.map((r) => r.status).join(','));
  R.check('duplicates are 409, not 500', regs.filter((r) => r.status !== 201).every((r) => r.status === 409), regs.map((r) => r.status).join(','));
  const orgSlug = `org-${rid()}`;
  const orgs = await Promise.all(Array.from({ length: 10 }, () => call('POST', '/organizations', { token: host.token, body: { name: 'Race Org', slug: orgSlug } })));
  R.check('10 parallel org creations with one slug: exactly 1', orgs.filter((r) => r.status === 201).length === 1 && orgs.every((r) => r.status < 500), orgs.map((r) => r.status).join(','));
  const pageSlug = `page-${rid()}`;
  const pages = await Promise.all(Array.from({ length: 10 }, () => call('POST', '/booking-pages', { token: host.token, body: { slug: pageSlug, title: 'p', durationMinutes: 30 } })));
  R.check('10 parallel booking-page creations with one slug: exactly 1', pages.filter((r) => r.status === 201).length === 1 && pages.every((r) => r.status < 500), pages.map((r) => r.status).join(','));

  // two admins demoting each other at the same time must leave one admin
  const a2 = await newUser('Second Admin');
  const org = orgs.find((r) => r.status === 201).json;
  await call('POST', `/organizations/${org.id}/members`, { token: host.token, body: { email: a2.email, role: 'ORGANIZATION_ADMIN' } });
  const demotes = await Promise.all([
    call('PATCH', `/organizations/${org.id}/members/${a2.id}`, { token: host.token, body: { role: 'MEMBER' } }),
    call('PATCH', `/organizations/${org.id}/members/${host.id}`, { token: a2.token, body: { role: 'MEMBER' } }),
  ]);
  const admins = (await call('GET', `/organizations/${org.id}/members`, { token: host.token })).json.filter((x) => x.role === 'ORGANIZATION_ADMIN').length;
  R.check('simultaneous mutual demotion keeps at least one org admin', admins >= 1, `admins=${admins} statuses=${demotes.map((r) => r.status).join(',')}`);

  // ------------------------------------------------------------------ webhook replay flood
  R.section = 'webhooks';
  const flood = await Promise.all(Array.from({ length: 100 }, () => call('POST', '/webhooks/livekit', { body: '{"event":"room_finished"}', headers: { 'Content-Type': 'application/webhook+json', Authorization: 'forged' } })));
  R.check('100 forged webhooks all rejected 401', flood.every((r) => r.status === 401), [...new Set(flood.map((r) => r.status))].join(','));

  // ------------------------------------------------------------------ meeting edit race (optimistic locking)
  R.section = 'optimistic locking';
  const e = (await call('POST', '/meetings', { token: host.token, body: { title: 'Edit race', type: 'SCHEDULED', timeZone: 'UTC', startTime: iso(Date.now() + 86400e3) } })).json;
  const edits = await Promise.all(Array.from({ length: 10 }, (_, i) => call('PATCH', `/meetings/${e.id}`, { token: host.token, body: { title: `Edit ${i}`, version: e.version } })));
  R.check('10 concurrent edits on one version: exactly 1 wins, rest 409', edits.filter((r) => r.status === 200).length === 1 && edits.filter((r) => r.status === 409).length === 9, edits.map((r) => r.status).join(','));

  return R.print();
}

main().then((failed) => process.exit(failed ? 1 : 0)).catch((e) => { console.error('FATAL', e); R.print(); process.exit(2); });
