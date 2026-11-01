import { expect, test, type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

export const LIVE = process.env.E2E_LIVE === '1';
export const PASSWORD = 'Correct-Horse-42!';

/** Skip the whole file unless E2E_LIVE=1. */
export function liveOnly() {
  test.skip(!LIVE, 'Live-stack suite: set E2E_LIVE=1 (needs web :5173, API :8081, LiveKit :7880 running)');
}

export function uniqueEmail(prefix: string) {
  return `${prefix}.${Date.now()}.${Math.floor(Math.random() * 1e6)}@e2e.test`;
}

export interface Diagnostics {
  label: string;
  consoleErrors: string[];
  pageErrors: string[];
  failedRequests: string[];
  httpErrors: string[];
}

const allDiagnostics = new Map<Page, Diagnostics>();

/** Records console errors, uncaught exceptions, network failures and HTTP >= 400 for a page. */
export function watch(page: Page, label: string): Diagnostics {
  const d: Diagnostics = { label, consoleErrors: [], pageErrors: [], failedRequests: [], httpErrors: [] };
  page.on('console', (m) => {
    if (m.type() === 'error') d.consoleErrors.push(m.text().slice(0, 400));
  });
  page.on('pageerror', (e) => d.pageErrors.push(`${e.name}: ${e.message}`.slice(0, 400)));
  page.on('requestfailed', (r) => {
    const err = r.failure()?.errorText ?? 'failed';
    // Aborted media/websocket requests on navigation are noise.
    if (err.includes('ERR_ABORTED')) return;
    d.failedRequests.push(`${r.method()} ${r.url()} — ${err}`);
  });
  page.on('response', (r) => {
    if (r.status() >= 400 && r.url().includes('/api/')) {
      d.httpErrors.push(`${r.request().method()} ${new URL(r.url()).pathname}${new URL(r.url()).search} → ${r.status()}`);
    }
  });
  allDiagnostics.set(page, d);
  return d;
}

/** Writes all diagnostics for the test into test-results/live-diagnostics/<test>.json and attaches them. */
export async function flushDiagnostics(testInfo: TestInfo) {
  // Snapshot and reset (pages may be shared across serial tests).
  const out = [...allDiagnostics.values()].map((d) => {
    const copy = structuredClone(d);
    d.consoleErrors.length = 0;
    d.pageErrors.length = 0;
    d.failedRequests.length = 0;
    d.httpErrors.length = 0;
    return copy;
  });
  for (const [p] of allDiagnostics) if (p.isClosed()) allDiagnostics.delete(p);
  const dir = path.join(process.cwd(), 'test-results', 'live-diagnostics');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${testInfo.titlePath.slice(1).join(' - ').replace(/[^\w.-]+/g, '_').slice(0, 120)}.json`);
  fs.writeFileSync(file, JSON.stringify({ test: testInfo.title, status: testInfo.status, pages: out }, null, 2));
  await testInfo.attach('diagnostics', { body: JSON.stringify(out, null, 2), contentType: 'application/json' });
}

export async function newContext(browser: Browser): Promise<BrowserContext> {
  return browser.newContext({
    permissions: ['camera', 'microphone'],
    timezoneId: 'UTC',
    locale: 'en-US',
    viewport: { width: 1400, height: 900 },
  });
}

/** Retries a step when the backend is briefly restarting. */
export async function withBackendRetry<T>(fn: () => Promise<T>, attempts = 3): Promise<T> {
  let last: unknown;
  for (let i = 0; i < attempts; i++) {
    try {
      return await fn();
    } catch (e) {
      last = e;
      if (i < attempts - 1) await new Promise((r) => setTimeout(r, 20_000));
    }
  }
  throw last;
}

/** Registers through the UI and signs in. Returns the credentials. */
export async function registerAndLogin(page: Page, name: string): Promise<{ email: string; password: string; name: string }> {
  const email = uniqueEmail(name.toLowerCase().replace(/\s+/g, '-'));
  await page.goto('/register');
  await page.getByLabel('Full name').fill(name);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Confirm password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByText('Account created')).toBeVisible();
  await page.getByRole('link', { name: 'Go to sign in' }).click();
  await login(page, email, PASSWORD);
  return { email, password: PASSWORD, name };
}

export async function login(page: Page, email: string, password: string) {
  await page.waitForURL(/\/login/);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL(/\/dashboard/);
}

/** Registers via the API only (fast path for secondary users). */
export async function registerViaApi(page: Page, name: string): Promise<{ email: string; password: string }> {
  const email = uniqueEmail(name.toLowerCase().replace(/\s+/g, '-'));
  const res = await page.request.post('/api/v1/auth/register', {
    data: { email, password: PASSWORD, displayName: name, timeZone: 'UTC' },
  });
  expect(res.status(), await res.text()).toBe(201);
  return { email, password: PASSWORD };
}

/** True when some remote participant tile has a <video> with a live video track. */
export async function expectRemoteVideo(page: Page) {
  await page.waitForFunction(
    () =>
      Array.from(document.querySelectorAll<HTMLVideoElement>('[data-lk-local-participant="false"] video')).some((v) => {
        const s = v.srcObject as MediaStream | null;
        return !!s && s.getVideoTracks().some((t) => t.readyState === 'live') && v.videoWidth > 0;
      }),
    undefined,
    { timeout: 45_000 },
  );
}

export async function expectParticipantCount(page: Page, n: number) {
  await expect(page.getByLabel(`${n} participants`, { exact: true }).first()).toBeVisible({ timeout: 30_000 });
}

/** Toast text (sonner). */
export function toast(page: Page, text: RegExp | string) {
  return page.locator('[data-sonner-toast]').filter({ hasText: text }).first();
}
