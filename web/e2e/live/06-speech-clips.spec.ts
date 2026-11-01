import { chromium, expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { PASSWORD, flushDiagnostics, liveOnly, newContext, registerAndLogin, registerViaApi, watch } from './helpers';

/*
 * Speaking clips against the real stack: each participant's browser records its own speaking turns and the server
 * files them under RECORDING_STORAGE_PATH (local stack: D:/meetly/Recordings). Fake microphones play WAV files:
 * syllable-like speech, or faint hiss (mic on, nobody speaks). A turn is ended by muting (immediate) and a new one is
 * started by unmuting.
 */

liveOnly();
test.describe.configure({ mode: 'serial' });

const STORAGE_ROOT = process.env.RECORDING_STORAGE_PATH ?? 'D:/meetly/Recordings';

function writeWav(file: string, sample: (t: number) => number, seconds = 4, rate = 48_000) {
  const n = seconds * rate;
  const buf = Buffer.alloc(44 + n * 2);
  buf.write('RIFF', 0);
  buf.writeUInt32LE(36 + n * 2, 4);
  buf.write('WAVEfmt ', 8);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(rate, 24);
  buf.writeUInt32LE(rate * 2, 28);
  buf.writeUInt16LE(2, 32);
  buf.writeUInt16LE(16, 34);
  buf.write('data', 36);
  buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, sample(i / rate))) * 32_767), 44 + i * 2);
  fs.writeFileSync(file, buf);
}

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'clips-e2e-'));
const SPEECH = path.join(dir, 'speech.wav');
const QUIET = path.join(dir, 'quiet.wav');
writeWav(SPEECH, (t) => {
  const syllable = t % 0.28;
  const env = syllable < 0.22 ? Math.sin((Math.PI * syllable) / 0.22) : 0;
  const f0 = 140 + 40 * Math.sin(2 * Math.PI * 0.7 * t);
  let s = 0;
  for (let h = 1; h <= 6; h++) s += Math.sin(2 * Math.PI * f0 * h * t) / h;
  return 0.45 * env * s;
});
writeWav(QUIET, () => (Math.random() * 2 - 1) * 0.0018);

const mediaArgs = (wav: string) => [
  '--use-fake-ui-for-media-stream',
  '--use-fake-device-for-media-stream',
  `--use-file-for-fake-audio-capture=${wav}`,
  '--autoplay-policy=no-user-gesture-required',
];
test.use({ launchOptions: { args: mediaArgs(SPEECH) } });

let quietBrowser: Browser;
const contexts: BrowserContext[] = [];
test.beforeAll(async () => {
  quietBrowser = await chromium.launch({ args: mediaArgs(QUIET) });
});
test.afterEach(async ({}, testInfo) => {
  await flushDiagnostics(testInfo);
});
test.afterAll(async () => {
  for (const c of contexts) await c.close().catch(() => undefined);
  await quietBrowser?.close();
});

async function page(browser: Browser, label: string): Promise<Page> {
  const ctx = await newContext(browser);
  contexts.push(ctx);
  const p = await ctx.newPage();
  watch(p, label);
  return p;
}

async function apiToken(p: Page, email: string): Promise<string> {
  const res = await p.request.post('/api/v1/auth/login', { data: { email, password: PASSWORD } });
  expect(res.status(), await res.text()).toBe(200);
  return ((await res.json()) as { accessToken: string }).accessToken;
}

interface Clip {
  id: string;
  participantName: string;
  fileName: string;
  fileSize: number;
  status: string;
  durationMs: number | null;
}

async function clips(p: Page, token: string, query: string): Promise<Clip[]> {
  const res = await p.request.get(`/api/v1/speech-recordings?${query}`, { headers: { Authorization: `Bearer ${token}` } });
  expect(res.status(), await res.text()).toBe(200);
  return ((await res.json()) as { items: Clip[] }).items;
}

test('each speaking turn becomes a clip filed by date, meeting and participant; only the right people can see it', async ({ browser }) => {
  const title = `Speech E2E ${Date.now()}`;
  const host = await page(browser, 'host');
  const hostUser = await registerAndLogin(host, 'Clip Host');
  await host.goto('/meetings/new');
  await host.getByLabel('Title').fill(title);
  await host.getByLabel('Meeting type').selectOption('INSTANT');
  const wr = host.getByRole('switch', { name: 'Waiting room' });
  if ((await wr.getAttribute('aria-checked')) === 'true') await wr.click();
  const rec = host.getByRole('switch', { name: 'Record automatically' });
  if ((await rec.getAttribute('aria-checked')) !== 'true') await rec.click();
  await host.getByRole('button', { name: 'Create & start' }).click();
  await host.waitForURL(/\/meetings\/[0-9a-f-]+\/room/);
  const meetingId = host.url().match(/meetings\/([0-9a-f-]+)\/room/)![1]!;
  await host.getByRole('button', { name: 'Join meeting' }).click();
  await expect(host.getByRole('contentinfo', { name: 'Meeting controls' })).toBeVisible();
  const details = await host.context().newPage();
  await details.goto(`/meetings/${meetingId}`);
  const code = (await details.locator('dt:has-text("Join code") + dd code').textContent())!.trim();
  await details.close();

  const vicky = await page(browser, 'vicky');
  await vicky.goto(`/j/${code}`);
  await expect(vicky.getByText('This meeting is recorded.')).toBeVisible();
  await vicky.getByLabel('Your name').fill('Vicky');
  await vicky.getByRole('button', { name: 'Join meeting' }).click();
  await expect(vicky.getByRole('contentinfo', { name: 'Meeting controls' })).toBeVisible({ timeout: 30_000 });
  const quinn = await page(quietBrowser, 'quinn');
  await quinn.goto(`/j/${code}`);
  await quinn.getByLabel('Your name').fill('Quiet Quinn');
  await quinn.getByRole('button', { name: 'Join meeting' }).click();
  await expect(quinn.getByRole('contentinfo', { name: 'Meeting controls' })).toBeVisible({ timeout: 30_000 });

  // Vicky speaks three times: speak, mute (turn ends), unmute (a new turn), ...
  for (let turn = 0; turn < 3; turn++) {
    await vicky.waitForTimeout(4_000);
    await vicky.getByRole('button', { name: 'Mute microphone' }).click();
    await vicky.waitForTimeout(1_500);
    if (turn < 2) await vicky.getByRole('button', { name: 'Unmute microphone' }).click();
  }
  // The host speaks once.
  await host.getByRole('button', { name: 'Mute microphone' }).click();

  const token = await apiToken(host, hostUser.email);
  let all: Clip[] = [];
  // Vicky: exactly three turns (each ended by a mute). The host spoke all along: at least one clip (a real gap of more
  // than 1.5 s in its audio, e.g. on an overloaded machine, rightly starts a new one).
  await expect
    .poll(async () => {
      all = await clips(host, token, `view=hosted&meetingId=${meetingId}`);
      const done = all.filter((c) => c.status === 'COMPLETED');
      return {
        vicky: done.filter((c) => c.participantName === 'Vicky').length,
        host: done.some((c) => c.participantName === 'Clip Host'),
        open: all.filter((c) => c.status === 'RECORDING').length,
      };
    }, { timeout: 30_000 })
    .toEqual({ vicky: 3, host: true, open: 0 });
  test.info().annotations.push({ type: 'clips', description: JSON.stringify(all) });
  // Mic on but silent: no clip.
  expect(all.filter((c) => c.participantName === 'Quiet Quinn')).toHaveLength(0);
  for (const c of all.filter((x) => x.status === 'COMPLETED')) {
    expect(c.fileName).toMatch(/^\d{12}_[0-9a-f]{6}\.webm$/);
    expect(c.fileSize).toBeGreaterThan(1_000);
    expect(c.durationMs ?? 0).toBeGreaterThan(1_000);
  }

  // On disk: Recordings/DD-MM-YYYY/<Meeting_Title>/<Participant>/ with real WebM files of the recorded sizes.
  const now = new Date();
  const day = `${String(now.getUTCDate()).padStart(2, '0')}-${String(now.getUTCMonth() + 1).padStart(2, '0')}-${now.getUTCFullYear()}`;
  const meetingDir = path.join(STORAGE_ROOT, day, title.replace(/\s+/g, '_'));
  const vickyFiles = fs.readdirSync(path.join(meetingDir, 'Vicky')).sort();
  expect(vickyFiles.filter((f) => f.endsWith('.webm'))).toHaveLength(3);
  expect(vickyFiles.filter((f) => f.endsWith('.part'))).toHaveLength(0);
  expect(fs.readdirSync(path.join(meetingDir, 'Clip_Host')).filter((f) => f.endsWith('.webm')).length).toBe(
    all.filter((c) => c.participantName === 'Clip Host' && c.status === 'COMPLETED').length,
  );
  expect(fs.existsSync(path.join(meetingDir, 'Quiet_Quinn'))).toBe(false);
  for (const c of all.filter((x) => x.status === 'COMPLETED')) {
    const folder = c.participantName === 'Vicky' ? 'Vicky' : 'Clip_Host';
    const file = path.join(meetingDir, folder, c.fileName);
    expect(fs.statSync(file).size).toBe(c.fileSize);
    expect([...fs.readFileSync(file).subarray(0, 4)]).toEqual([0x1a, 0x45, 0xdf, 0xa3]);
  }

  // Someone else (in a browser of their own: a login sets that browser's session cookie) cannot see or play the host's
  // clips (404: nothing revealed).
  const otherPage = await page(browser, 'other');
  const other = await registerViaApi(otherPage, 'Other Person');
  const otherToken = await apiToken(otherPage, other.email);
  expect(await clips(otherPage, otherToken, 'view=hosted')).toHaveLength(0);
  const denied = await otherPage.request.post(`/api/v1/speech-recordings/${all[0]!.id}/access-url`, {
    headers: { Authorization: `Bearer ${otherToken}` },
  });
  expect(denied.status()).toBe(404);

  // The host's dashboard: filter by participant, and play a clip in the browser.
  await host.goto('/recordings');
  await expect(host.getByRole('heading', { name: 'Speaking clips' })).toBeVisible();
  await host.getByLabel('Filter by meeting').selectOption({ label: title });
  await host.getByLabel('Filter by participant').selectOption({ label: 'Vicky' });
  const list = host.getByRole('list', { name: 'Speaking clips' }).getByRole('listitem');
  await expect(list).toHaveCount(3);
  await list.first().getByRole('button', { name: /^Play clip of Vicky/ }).click();
  await host.waitForFunction(() => {
    const m = document.querySelector<HTMLMediaElement>('[role="dialog"] audio, [role="dialog"] video');
    return !!m && m.readyState >= 1 && !m.error;
  }, undefined, { timeout: 20_000 });
});
