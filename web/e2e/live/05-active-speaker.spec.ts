import { chromium, expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { flushDiagnostics, liveOnly, newContext, registerAndLogin, watch } from './helpers';

/*
 * Active-speaker highlighting against the real stack (LiveKit's voice-activity detection on real audio):
 * Chromium's fake microphone plays a WAV file in a loop. "Speech" is a syllable-like signal (harmonics with a moving
 * pitch, 220 ms syllables, short gaps): not stationary, so the browser's noise suppression does not remove it.
 * "Quiet" is faint background hiss (about -55 dBFS): the microphone is on, nobody speaks.
 */

liveOnly();
test.describe.configure({ mode: 'serial' });

function writeWav(file: string, sample: (t: number) => number, seconds = 4, rate = 48_000) {
  const n = seconds * rate;
  const buf = Buffer.alloc(44 + n * 2);
  buf.write('RIFF', 0);
  buf.writeUInt32LE(36 + n * 2, 4);
  buf.write('WAVEfmt ', 8);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20); // PCM
  buf.writeUInt16LE(1, 22); // mono
  buf.writeUInt32LE(rate, 24);
  buf.writeUInt32LE(rate * 2, 28);
  buf.writeUInt16LE(2, 32);
  buf.writeUInt16LE(16, 34);
  buf.write('data', 36);
  buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) {
    const v = Math.max(-1, Math.min(1, sample(i / rate)));
    buf.writeInt16LE(Math.round(v * 32_767), 44 + i * 2);
  }
  fs.writeFileSync(file, buf);
}

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'speaker-e2e-'));
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
// Steady, louder background noise (a fan or air conditioning, about -30 dBFS): mic on, nobody speaks.
const NOISY = path.join(dir, 'noisy.wav');
let brown = 0;
writeWav(NOISY, () => {
  brown = Math.max(-1, Math.min(1, brown * 0.98 + (Math.random() * 2 - 1) * 0.08));
  return 0.045 * brown + (Math.random() * 2 - 1) * 0.012;
});
// A plain grey camera (Chromium's default fake camera is green, which hides a green frame in screenshots).
const GREY = path.join(dir, 'grey.y4m');
{
  const w = 320, h = 240, frames = 15;
  const header = Buffer.from(`YUV4MPEG2 W${w} H${h} F15:1 Ip A1:1 C420jpeg\n`);
  const frame = Buffer.concat([Buffer.from('FRAME\n'), Buffer.alloc(w * h, 90), Buffer.alloc((w * h) / 2, 128)]);
  fs.writeFileSync(GREY, Buffer.concat([header, ...Array.from({ length: frames }, () => frame)]));
}

const mediaArgs = (wav: string) => [
  '--use-fake-ui-for-media-stream',
  '--use-fake-device-for-media-stream',
  `--use-file-for-fake-audio-capture=${wav}`,
  `--use-file-for-fake-video-capture=${GREY}`,
  '--autoplay-policy=no-user-gesture-required',
];

test.use({ launchOptions: { args: mediaArgs(SPEECH) } });

let quietBrowser: Browser;
let noisyBrowser: Browser;
const contexts: BrowserContext[] = [];

test.beforeAll(async () => {
  quietBrowser = await chromium.launch({ args: mediaArgs(QUIET) });
  noisyBrowser = await chromium.launch({ args: mediaArgs(NOISY) });
});

test.afterEach(async ({}, testInfo) => {
  await flushDiagnostics(testInfo);
});

test.afterAll(async () => {
  for (const c of contexts) await c.close().catch(() => undefined);
  await quietBrowser?.close();
  await noisyBrowser?.close();
});

async function page(browser: Browser, label: string): Promise<Page> {
  const ctx = await newContext(browser);
  contexts.push(ctx);
  const p = await ctx.newPage();
  watch(p, label);
  return p;
}

/** The host creates and starts an instant meeting (no waiting room) and joins it with camera and microphone on. */
async function startMeeting(host: Page, title: string, visibility: 'EVERYONE_VISIBLE' | 'HOST_AND_SELF_ONLY'): Promise<string> {
  await host.goto('/meetings/new');
  await host.getByLabel('Title').fill(title);
  await host.getByLabel('Meeting type').selectOption('INSTANT');
  await host.getByLabel('Who can see whom').selectOption(visibility);
  const wr = host.getByRole('switch', { name: 'Waiting room' });
  if ((await wr.getAttribute('aria-checked')) === 'true') await wr.click();
  await host.getByRole('button', { name: 'Create & start' }).click();
  await host.waitForURL(/\/meetings\/[0-9a-f-]+\/room/);
  const meetingId = host.url().match(/meetings\/([0-9a-f-]+)\/room/)![1]!;
  await host.getByRole('button', { name: 'Join meeting' }).click();
  await expect(host.getByRole('contentinfo', { name: 'Meeting controls' })).toBeVisible();
  const details = await host.context().newPage();
  await details.goto(`/meetings/${meetingId}`);
  const code = (await details.locator('dt:has-text("Join code") + dd code').textContent())!.trim();
  await details.close();
  return code;
}

async function joinAsGuest(p: Page, code: string, name: string) {
  await p.goto(`/j/${code}`);
  await p.getByLabel('Your name').fill(name);
  await p.getByRole('button', { name: 'Join meeting' }).click();
  await expect(p.getByRole('contentinfo', { name: 'Meeting controls' })).toBeVisible({ timeout: 30_000 });
}

/** The tile wrapper of the participant with this name (the name LiveKit shows on the tile). */
function tile(p: Page, name: string) {
  return p.locator('.lk-grid-layout > div, .lk-carousel > div').filter({ has: p.locator('.lk-participant-name', { hasText: name }) }).first();
}

const speaking = (p: Page) => p.locator('[data-speaking="true"]');

/** Computed look of a tile's frame, and of LiveKit's own outline (which must be off). */
async function frameStyle(p: Page, name: string) {
  return tile(p, name).evaluate((w) => {
    const f = getComputedStyle(w.querySelector('.speaking-frame')!);
    const tileEl = w.querySelector('.lk-participant-tile')!;
    const after = getComputedStyle(tileEl, '::after');
    const fr = w.querySelector('.speaking-frame')!.getBoundingClientRect();
    const tr = tileEl.getBoundingClientRect();
    return {
      style: f.borderTopStyle,
      width: f.borderTopWidth,
      color: f.borderTopColor,
      opacity: f.opacity,
      shadow: f.boxShadow,
      lkIndicatorWidth: getComputedStyle(tileEl).getPropertyValue('--lk-speaking-indicator-width').trim(),
      lkAfterDisplay: after.display,
      misalignment: Math.max(Math.abs(fr.top - tr.top), Math.abs(fr.bottom - tr.bottom), Math.abs(fr.left - tr.left), Math.abs(fr.right - tr.right)),
    };
  });
}

test('everyone visible: speakers framed (several at once), silent and noisy mics not framed, mute ends it at once, leaving cleans up', async ({ browser }) => {
  const host = await page(browser, 'host');
  await registerAndLogin(host, 'Speaker Host');
  const code = await startMeeting(host, `Speaker E2E ${Date.now()}`, 'EVERYONE_VISIBLE');
  const sam = await page(browser, 'sam');
  await joinAsGuest(sam, code, 'Speaker Sam');
  const quinn = await page(quietBrowser, 'quinn');
  await joinAsGuest(quinn, code, 'Quiet Quinn');
  const nora = await page(noisyBrowser, 'nora');
  await joinAsGuest(nora, code, 'Noisy Nora');

  // One tile per person, no duplicates.
  await expect(host.locator('.lk-participant-tile')).toHaveCount(4, { timeout: 30_000 });
  // Two people speaking at the same time: both framed, on the host's screen.
  await expect(tile(host, 'Speaker Sam')).toHaveAttribute('data-speaking', 'true', { timeout: 20_000 });
  await expect(tile(host, 'Speaker Host')).toHaveAttribute('data-speaking', 'true');
  // Microphone on with faint hiss, and with steady fan-like noise: never framed (watched for 6 s).
  for (let i = 0; i < 12; i++) {
    await expect(tile(host, 'Quiet Quinn')).not.toHaveAttribute('data-speaking', 'true');
    await expect(tile(host, 'Noisy Nora')).not.toHaveAttribute('data-speaking', 'true');
    await host.waitForTimeout(500);
  }
  // Another viewer sees the same (derived from the server's detection, not from local state).
  await expect(tile(quinn, 'Speaker Sam')).toHaveAttribute('data-speaking', 'true');

  // What is drawn: a solid green frame on the tile's edges with an inner glow; LiveKit's own outline is off.
  await host.waitForTimeout(300); // after the 150 ms fade-in
  const on = await frameStyle(host, 'Speaker Sam');
  test.info().annotations.push({ type: 'speaking-frame', description: JSON.stringify(on) });
  expect(on).toMatchObject({ style: 'solid', width: '3px', opacity: '1', lkIndicatorWidth: '0px', lkAfterDisplay: 'none' });
  expect(on.color).not.toMatch(/rgba\(0, 0, 0, 0\)|transparent/);
  expect(on.shadow).toContain('inset');
  expect(on.misalignment).toBeLessThanOrEqual(1);
  const off = await frameStyle(host, 'Quiet Quinn');
  expect(off).toMatchObject({ opacity: '0', lkIndicatorWidth: '0px', lkAfterDisplay: 'none' });
  await host.screenshot({ path: test.info().outputPath('host-speaking.png') });

  // Sam mutes: the frame goes at once. Without the hard stop it would take the server's next report plus the
  // 600 ms silence threshold, so anything under 550 ms (click and network included) proves the immediate path.
  const muteStart = Date.now();
  await sam.getByRole('button', { name: 'Mute microphone' }).click();
  await expect(tile(host, 'Speaker Sam')).not.toHaveAttribute('data-speaking', 'true', { timeout: 2_000 });
  const muteMs = Date.now() - muteStart;
  test.info().annotations.push({ type: 'mute-to-unframed-ms', description: String(muteMs) });
  expect(muteMs).toBeLessThan(550);
  // Unmute: framed again once the server reports speech.
  await sam.getByRole('button', { name: 'Unmute microphone' }).click();
  await expect(tile(host, 'Speaker Sam')).toHaveAttribute('data-speaking', 'true', { timeout: 10_000 });

  // Sam leaves while speaking: the tile disappears and the only frame left is the host's own (still speaking).
  await sam.close();
  await expect(tile(host, 'Speaker Sam')).toHaveCount(0, { timeout: 30_000 });
  await expect(host.locator('.lk-participant-tile')).toHaveCount(3);
  await expect(speaking(host)).toHaveCount(1);
  await expect(tile(host, 'Speaker Host')).toHaveAttribute('data-speaking', 'true');
});

test('host and self only: the host sees every speaking attendee; an attendee sees only the host and itself', async ({ browser }) => {
  const host = await page(browser, 'webinar-host');
  await registerAndLogin(host, 'Webinar Host');
  const code = await startMeeting(host, `Webinar Speaker E2E ${Date.now()}`, 'HOST_AND_SELF_ONLY');
  const ann = await page(browser, 'ann');
  await joinAsGuest(ann, code, 'Attendee Ann');
  const bob = await page(browser, 'bob');
  await joinAsGuest(bob, code, 'Attendee Bob');

  // Host: its own tile plus one per attendee, all three framed while speaking.
  await expect(host.locator('.lk-participant-tile')).toHaveCount(3, { timeout: 30_000 });
  await expect(speaking(host)).toHaveCount(3, { timeout: 30_000 });
  await host.screenshot({ path: test.info().outputPath('webinar-host.png') });

  // Ann: exactly the host and herself, both framed while speaking; nothing at all about Bob.
  await expect(ann.locator('.lk-participant-tile')).toHaveCount(2, { timeout: 20_000 });
  const remote = ann.locator('.speaking-tile').filter({ has: ann.locator('[data-lk-local-participant="false"]') });
  const own = ann.locator('.speaking-tile').filter({ has: ann.locator('[data-lk-local-participant="true"]') });
  await expect(remote).toHaveCount(1);
  await expect(remote.locator('.lk-participant-name')).toHaveText('Webinar Host');
  await expect(remote).toHaveAttribute('data-speaking', 'true', { timeout: 20_000 });
  await expect(own).toHaveCount(1);
  await expect(own).toHaveAttribute('data-speaking', 'true', { timeout: 20_000 });
  await ann.waitForTimeout(3_000);
  await expect(ann.locator('.lk-participant-tile')).toHaveCount(2);
  await expect(ann.getByText('Attendee Bob')).toHaveCount(0);
  await expect(speaking(ann)).toHaveCount(2);
  await ann.screenshot({ path: test.info().outputPath('webinar-attendee.png') });

  // Ann's own row in the participants panel lights up too (it comes from her audience room, where she speaks).
  await ann.getByRole('contentinfo', { name: 'Meeting controls' }).getByRole('button', { name: /^Participants/ }).click();
  const ownRow = ann.locator('li').filter({ hasText: '(you)' });
  await expect(ownRow).toHaveCount(1);
  await expect(ownRow.locator('.ring-success')).toHaveCount(1, { timeout: 10_000 });
  await expect(ann.getByRole('list').getByText('Attendee Bob')).toHaveCount(0);
});
