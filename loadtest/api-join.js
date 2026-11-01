// k6 API load test for the control plane: sign-in, create meeting, join (token issuance), poll, leave.
// It does NOT carry media - media load is generated with `lk load-test` (see run-media-load.sh).
//
//   k6 run -e BASE_URL=https://meet.example.com -e USERS_FILE=users.json loadtest/api-join.js
//
// users.json: [{"email":"load1@example.com","password":"..."}...] - pre-created, verified accounts.
// Scenario: ROOMS rooms (default 100) x PARTICIPANTS each (default 10) = 1,000 joins ramped over 5 minutes.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:3000';
const ROOMS = Number(__ENV.ROOMS || 100);
const PER_ROOM = Number(__ENV.PARTICIPANTS || 10);
const users = new SharedArray('users', () => JSON.parse(open(__ENV.USERS_FILE || './users.json')));

const joinLatency = new Trend('join_latency', true);
const joinFailures = new Rate('join_failures');

export const options = {
  scenarios: {
    hosts: {
      executor: 'per-vu-iterations', vus: ROOMS, iterations: 1, exec: 'hostRoom', maxDuration: '20m',
    },
  },
  thresholds: {
    // Release targets (docs/08-performance-testing.md)
    join_latency: ['p(95)<500', 'p(99)<1500'],
    join_failures: ['rate<0.01'],
    http_req_failed: ['rate<0.01'],
  },
};

function login(user) {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ ...user, clientType: 'NATIVE' }),
    { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'login 200': (r) => r.status === 200 });
  return res.json('accessToken');
}

export function hostRoom() {
  const host = users[(__VU - 1) % users.length];
  const token = login(host);
  const auth = { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
  const meeting = http.post(`${BASE}/api/v1/meetings`, JSON.stringify({
    title: `Load room ${__VU}`, type: 'INSTANT', timeZone: 'UTC', maxParticipants: PER_ROOM + 2,
  }), auth);
  check(meeting, { 'meeting created': (r) => r.status === 201 });
  const meetingId = meeting.json('id');

  const hostJoin = http.post(`${BASE}/api/v1/meetings/${meetingId}/join`, '{}', auth);
  check(hostJoin, { 'host admitted': (r) => r.json('status') === 'ADMITTED' });

  for (let i = 1; i < PER_ROOM; i += 1) {
    const res = http.post(`${BASE}/api/v1/meetings/${meetingId}/join`,
      JSON.stringify({ displayName: `Guest ${__VU}-${i}` }), { headers: { 'Content-Type': 'application/json' } });
    joinLatency.add(res.timings.duration);
    joinFailures.add(res.status !== 200 || res.json('status') !== 'ADMITTED');
    sleep(Math.random() * 2);
  }
  sleep(30);
  http.post(`${BASE}/api/v1/meetings/${meetingId}/end`, null, auth);
}
