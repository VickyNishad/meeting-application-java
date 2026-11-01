// Resilience suite: dependency failures and restarts must degrade cleanly and recover without data loss.
// Usage: node qa/resilience.mjs   (Windows local no-Docker stack; needs the scripts in %USERPROFILE%\.meet-tools)
import { execSync, spawn } from 'node:child_process';
import { BASE, call, newUser, sleep, Report } from './lib.mjs';

const R = new Report('Resilience suite');
const API_PORT = Number(new URL(BASE).port || 80);
const TOOLS = `${process.env.USERPROFILE}\\.meet-tools`;

function pidOnPort(port) {
  try {
    const out = execSync(`powershell -NoProfile -Command "(Get-NetTCPConnection -LocalPort ${port} -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess"`).toString().trim();
    return out ? Number(out) : null;
  } catch { return null; }
}
function commandLineOf(pid) {
  try {
    return execSync(`powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}').CommandLine"`).toString();
  } catch { return ''; }
}
/** Kills the listener on `port` only if its command line matches `expected`, so an unrelated app is never touched. */
function kill(port, expected) {
  const pid = pidOnPort(port);
  if (!pid) return null;
  if (!expected.test(commandLineOf(pid))) throw new Error(`refusing to kill pid ${pid} on port ${port}: not ${expected}`);
  execSync(`powershell -NoProfile -Command "Stop-Process -Id ${pid} -Force"`);
  return pid;
}
function startDetached(cmd) {
  spawn('powershell', ['-NoProfile', '-Command', cmd], { detached: true, stdio: 'ignore', windowsHide: true }).unref();
}
async function waitFor(fn, timeoutMs = 120000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    if (await fn()) return true;
    await sleep(2000);
  }
  return false;
}
const apiReady = async () => (await call('GET', '/../../actuator/health/readiness')).status === 200;
const livekitUp = async () => { try { return (await fetch('http://127.0.0.1:7880')).status === 200; } catch { return false; } };

async function main() {
  const host = await newUser('Resilience Host');
  const meeting = (await call('POST', '/meetings', { token: host.token, body: { title: 'Survives restarts', type: 'INSTANT', timeZone: 'UTC' } })).json;
  const hostJoin = (await call('POST', `/meetings/${meeting.id}/join`, { token: host.token })).json;
  const guest = (await call('POST', `/meetings/${meeting.id}/join`, { body: { displayName: 'Guest' } })).json;
  await call('POST', `/meetings/${meeting.id}/chat`, { headers: { 'X-Participant-Token': guest.waitToken }, body: { participantId: guest.participantId, text: 'before restart' } });

  // ------------------------------------------------------------------ API restart mid-meeting
  R.section = 'api restart';
  R.check('api killed', kill(API_PORT, /meet-platform.*\.jar/));
  const down = await call('GET', '/users/me', { token: host.token });
  R.check('requests fail fast while API is down (connection refused)', down.status === 0, `${down.status}`);
  startDetached(`& '${TOOLS}\\start-api.ps1'`);
  R.check('api back within 120 s', await waitFor(apiReady));
  R.status('access token still valid after restart (stateless JWT)', await call('GET', '/users/me', { token: host.token }), 200);
  const status = await call('GET', `/meetings/${meeting.id}/join-status?participantId=${guest.participantId}`, { headers: { 'X-Participant-Token': guest.waitToken } });
  R.check('guest reconnect token still issued after restart', status.json?.status === 'ADMITTED' && status.json?.roomName === hostJoin.roomName, status.text.slice(0, 200));
  const hist = await call('GET', `/meetings/${meeting.id}/chat`, { headers: { 'X-Participant-Token': guest.waitToken } });
  R.check('chat history survived restart', hist.json?.some((m) => m.text === 'before restart'), hist.text.slice(0, 200));
  R.status('meeting still LIVE', await call('GET', `/meetings/${meeting.id}`, { token: host.token }), 200);

  // ------------------------------------------------------------------ media server outage
  R.section = 'livekit outage';
  R.check('livekit killed', kill(7880, /livekit-server/));
  await sleep(1000);
  const m2 = (await call('POST', '/meetings', { token: host.token, body: { title: 'During outage', type: 'INSTANT', timeZone: 'UTC' } })).json;
  const startDuringOutage = await call('POST', `/meetings/${m2.id}/join`, { token: host.token });
  R.status('starting a meeting during SFU outage returns 503 MEDIA_PROVIDER_UNAVAILABLE', startDuringOutage, 503, 'MEDIA_PROVIDER_UNAVAILABLE');
  R.check('outage response arrives within the configured timeout (<25 s)', startDuringOutage.ms < 25000, `${startDuringOutage.ms.toFixed(0)} ms`);
  const afterFail = await call('GET', `/meetings/${m2.id}`, { token: host.token });
  R.check('failed start leaves meeting SCHEDULED (no half-created room)', afterFail.json?.status === 'SCHEDULED', afterFail.json?.status);
  R.status('non-media endpoints keep working during SFU outage', await call('GET', '/meetings?scope=live', { token: host.token }), 200);
  R.status('API stays ready during SFU outage', await call('GET', '/../../actuator/health/readiness'), 200);
  startDetached(`& '${TOOLS}\\livekit\\livekit-server.exe' --config '${TOOLS}\\livekit-local.yaml' *>> '${TOOLS}\\livekit.log'`);
  R.check('livekit back', await waitFor(livekitUp, 60000));
  const retry = await call('POST', `/meetings/${m2.id}/join`, { token: host.token });
  R.check('meeting can start once SFU is back (no retry needed by admin)', retry.json?.status === 'ADMITTED', retry.text.slice(0, 200));
  // The first meeting's LiveKit room was lost with the SFU process; ending it must still settle cleanly.
  R.status('ending a meeting whose SFU room vanished succeeds', await call('POST', `/meetings/${meeting.id}/end`, { token: host.token }), 204);
  R.status('second meeting ends', await call('POST', `/meetings/${m2.id}/end`, { token: host.token }), 204);

  // ------------------------------------------------------------------ database outage
  R.section = 'database outage';
  const pg = kill(5432, /postgres/i);
  R.check('postgres killed', pg);
  await sleep(2000);
  const dbDown = await call('GET', '/meetings?scope=upcoming', { token: host.token });
  R.check('DB outage gives a clean 5xx problem response, no stack trace', dbDown.status >= 500 && dbDown.status < 600 && !/Exception|\.java/.test(dbDown.text), `${dbDown.status} ${dbDown.text.slice(0, 200)}`);
  const ready = await call('GET', '/../../actuator/health/readiness');
  R.check('readiness reports DOWN so the load balancer drains the instance', ready.status === 503, `${ready.status}`);
  const live = await call('GET', '/../../actuator/health/liveness');
  R.check('liveness stays UP (no restart loop for a dependency outage)', live.status === 200, `${live.status}`);
  startDetached(`& '${TOOLS}\\pg\\bin\\pg_ctl.exe' -D '${TOOLS}\\pgdata' -l '${TOOLS}\\pg.log' -o '-p 5432 -c listen_addresses=127.0.0.1' start`);
  R.check('API recovers by itself when DB returns (pool reconnects)', await waitFor(apiReady, 90000));
  R.status('requests succeed again', await call('GET', '/meetings?scope=past', { token: host.token }), 200);

  return R.print();
}

main().then((failed) => process.exit(failed ? 1 : 0)).catch((e) => { console.error('FATAL', e); R.print(); process.exit(2); });
