// Control-plane load test (no dependencies). Real API + real LiveKit room creation and token signing.
// Usage: node qa/load.mjs [rooms=100] [perRoom=10] [concurrency=50]
// Media load (WebRTC streams) is separate: see docs/08-performance-testing.md.
import { call, newUser, pool, percentiles, rid, Report } from './lib.mjs';

const ROOMS = Number(process.argv[2] || 100);
const PER_ROOM = Number(process.argv[3] || 10);
const CONCURRENCY = Number(process.argv[4] || 50);
const R = new Report(`Load test: ${ROOMS} rooms x ${PER_ROOM} participants, concurrency ${CONCURRENCY}`);
const table = [];

async function phase(name, items, concurrency, fn, targets) {
  const started = performance.now();
  const results = await pool(items, concurrency, fn);
  const seconds = (performance.now() - started) / 1000;
  const flat = results.flat().filter(Boolean);
  const errors = flat.filter((r) => r.status === 0 || r.status >= 500);
  const lat = percentiles(flat.map((r) => r.ms));
  table.push({ phase: name, requests: flat.length, rps: (flat.length / seconds).toFixed(0), p50: lat.p50.toFixed(0), p95: lat.p95.toFixed(0), p99: lat.p99.toFixed(0), max: lat.max.toFixed(0), errors: errors.length });
  R.section = name;
  R.check(`error rate < 1% (${errors.length}/${flat.length})`, errors.length / flat.length < 0.01, [...new Set(errors.map((e) => `${e.status} ${e.text.slice(0, 120)}`))].slice(0, 3).join(' | '));
  if (targets?.p95) R.check(`p95 < ${targets.p95} ms (${lat.p95.toFixed(0)})`, lat.p95 < targets.p95, `p95=${lat.p95.toFixed(0)}`);
  if (targets?.p99) R.check(`p99 < ${targets.p99} ms (${lat.p99.toFixed(0)})`, lat.p99 < targets.p99, `p99=${lat.p99.toFixed(0)}`);
  return results;
}

async function main() {
  console.log(`Preparing ${ROOMS} host accounts...`);
  const hosts = await pool(Array.from({ length: ROOMS }), 10, (_, i) => newUser(`Load Host ${i}`));

  await phase('auth: login burst', hosts.concat(hosts), CONCURRENCY,
    (h) => call('POST', '/auth/login', { body: { email: h.email, password: 'a long local test passphrase', clientType: 'NATIVE' } }),
    { p95: 1500 }); // bcrypt is deliberately slow (~100 ms CPU per login)

  const meetings = await phase('create meetings', hosts, CONCURRENCY,
    (h) => call('POST', '/meetings', { token: h.token, body: { title: `Load ${rid()}`, type: 'INSTANT', timeZone: 'UTC', maxParticipants: PER_ROOM + 2 } }),
    { p95: 500 });

  const hostJoins = await phase('host join (creates LiveKit room)', hosts.map((h, i) => ({ h, m: meetings[i].json })), CONCURRENCY,
    ({ h, m }) => call('POST', `/meetings/${m.id}/join`, { token: h.token }), { p95: 800 });

  const guestJobs = meetings.flatMap((m, i) => Array.from({ length: PER_ROOM - 1 }, (_, g) => ({ m: m.json, g, room: i })));
  const guestJoins = await phase('guest join (token issuance)', guestJobs, CONCURRENCY,
    ({ m, g }) => call('POST', `/meetings/${m.id}/join`, { body: { displayName: `Guest ${g}` } }), { p95: 500, p99: 1500 });
  R.section = 'guest join (token issuance)';
  const admitted = guestJoins.filter((r) => r.json?.status === 'ADMITTED').length;
  R.check(`all ${guestJobs.length} guests admitted with a token`, admitted === guestJobs.length, `admitted=${admitted}`);
  R.check('total participants = rooms x perRoom', admitted + hostJoins.filter((r) => r.json?.token).length === ROOMS * PER_ROOM, `${admitted}+hosts`);

  const chatJobs = guestJoins.map((r, i) => ({ r, m: guestJobs[i].m })).filter((x) => x.r.json?.participantId);
  await phase('chat burst (persist + relay)', chatJobs.concat(chatJobs), CONCURRENCY * 2,
    ({ r, m }) => call('POST', `/meetings/${m.id}/chat`, { headers: { 'X-Participant-Token': r.json.waitToken }, body: { participantId: r.json.participantId, text: `load message ${rid()}` } }),
    { p95: 500 });

  await phase('join-status polling (reconnect path)', chatJobs, CONCURRENCY * 2,
    ({ r, m }) => call('GET', `/meetings/${m.id}/join-status?participantId=${r.json.participantId}`, { headers: { 'X-Participant-Token': r.json.waitToken } }),
    { p95: 300 });

  const readJobs = hosts.flatMap((h, i) => [
    () => call('GET', '/meetings?scope=live', { token: h.token }),
    () => call('GET', `/meetings/${meetings[i].json.id}`, { token: h.token }),
    () => call('GET', `/meetings/${meetings[i].json.id}/participants`, { token: h.token }),
    () => call('GET', `/meetings/${meetings[i].json.id}/chat`, { token: h.token }),
    () => call('GET', '/users/me', { token: h.token }),
    () => call('GET', '/notifications', { token: h.token }),
  ]);
  await phase('read-heavy dashboard traffic', readJobs.concat(readJobs, readJobs), CONCURRENCY * 2, (fn) => fn(), { p95: 300 });

  const owner = hosts[0];
  await call('PUT', '/availability', { token: owner.token, body: { timeZone: 'America/New_York', weeklyRules: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'].map((d) => ({ dayOfWeek: d, startTime: '09:00', endTime: '17:00' })) } });
  const slug = `load-${rid()}`;
  await call('POST', '/booking-pages', { token: owner.token, body: { slug, title: 'Load', durationMinutes: 30, minNoticeMinutes: 0 } });
  const from = new Date(Date.now() + 86400e3).toISOString().slice(0, 10);
  const to = new Date(Date.now() + 30 * 86400e3).toISOString().slice(0, 10);
  await phase('public slot computation (30-day window)', Array.from({ length: 500 }), CONCURRENCY,
    () => call('GET', `/booking-pages/public/${slug}/slots?from=${from}&to=${to}&timeZone=Asia/Kolkata`), { p95: 500 });

  await phase('moderation mute-all across rooms', hosts.map((h, i) => ({ h, m: meetings[i].json })), CONCURRENCY,
    ({ h, m }) => call('POST', `/meetings/${m.id}/moderation/mute-all`, { token: h.token }), { p95: 1500 });

  await phase('end all rooms (deletes LiveKit rooms)', hosts.map((h, i) => ({ h, m: meetings[i].json })), CONCURRENCY,
    ({ h, m }) => call('POST', `/meetings/${m.id}/end`, { token: h.token }), { p95: 800 });

  console.table(table);
  return R.print();
}

main().then((failed) => process.exit(failed ? 1 : 0)).catch((e) => { console.error('FATAL', e); console.table(table); R.print(); process.exit(2); });
