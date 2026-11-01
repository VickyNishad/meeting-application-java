import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import {
  expectParticipantCount,
  expectRemoteVideo,
  flushDiagnostics,
  liveOnly,
  newContext,
  registerAndLogin,
  toast,
  watch,
} from './helpers';

liveOnly();
test.describe.configure({ mode: 'serial' });

let hostCtx: BrowserContext;
let guestCtx: BrowserContext;
let host: Page;
let guest: Page;
let meetingId = '';
let joinCode = '';
const title = `Live E2E ${Date.now()}`;
const GUEST = 'Guest Gina';
const LATE = 'Late Larry';

test.beforeAll(async ({ browser }) => {
  hostCtx = await newContext(browser);
  guestCtx = await newContext(browser);
  host = await hostCtx.newPage();
  guest = await guestCtx.newPage();
  watch(host, 'host');
  watch(guest, 'guest');
  await registerAndLogin(host, 'Host Hannah');
});

test.afterEach(async ({}, testInfo) => {
  await flushDiagnostics(testInfo);
});

test.afterAll(async () => {
  await hostCtx?.close();
  await guestCtx?.close();
});

async function openPanel(page: Page, name: RegExp) {
  const btn = page.getByRole('contentinfo', { name: 'Meeting controls' }).getByRole('button', { name });
  if ((await btn.getAttribute('aria-pressed')) !== 'true') await btn.click();
}

async function participantAction(item: RegExp | string, who = GUEST) {
  await openPanel(host, /^Participants$/);
  await host.getByRole('button', { name: `Actions for ${who}` }).click();
  await host.getByRole('menuitem', { name: item }).click();
}

test('2. instant meeting with waiting room: pre-join, guest admitted, video both ways', async () => {
  await host.goto('/meetings/new');
  await host.getByLabel('Title').fill(title);
  await host.getByLabel('Meeting type').selectOption('INSTANT');
  const wr = host.getByRole('switch', { name: 'Waiting room' });
  if ((await wr.getAttribute('aria-checked')) !== 'true') await wr.click();
  await expect(wr).toHaveAttribute('aria-checked', 'true');
  await host.getByRole('button', { name: 'Create & start' }).click();
  await host.waitForURL(/\/meetings\/[0-9a-f-]+\/room/);
  meetingId = host.url().match(/meetings\/([0-9a-f-]+)\/room/)![1]!;

  // Pre-join device test: live camera preview + mic meter + device selects
  await expect(host.getByRole('heading', { name: title })).toBeVisible();
  await host.waitForFunction(() => {
    const v = document.querySelector<HTMLVideoElement>('video[aria-label="Camera preview"]');
    const s = v?.srcObject as MediaStream | null;
    return !!s && s.getVideoTracks()[0]?.readyState === 'live' && (v?.videoWidth ?? 0) > 0;
  });
  await expect(host.getByRole('meter', { name: 'Microphone level' })).toBeVisible();
  await expect(host.getByLabel('Camera', { exact: true })).toBeEnabled();
  await expect(host.getByLabel('Microphone', { exact: true })).toBeEnabled();
  await host.getByRole('button', { name: 'Join meeting' }).click();
  await expect(host.getByRole('contentinfo', { name: 'Meeting controls' })).toBeVisible();
  await expectParticipantCount(host, 1);

  // Join code from the meeting details (separate tab of the host context)
  const details = await hostCtx.newPage();
  watch(details, 'host-details');
  await details.goto(`/meetings/${meetingId}`);
  joinCode = (await details.locator('dt:has-text("Join code") + dd code').textContent())!.trim();
  expect(joinCode).toBeTruthy();
  await expect(details.getByText('Live').first()).toBeVisible();
  await details.close();

  // Guest: join link → name → waiting room
  await guest.goto(`/j/${joinCode}`);
  await guest.getByLabel('Your name').fill(GUEST);
  await guest.getByRole('button', { name: 'Join meeting' }).click();
  await expect(guest.getByRole('heading', { name: 'Waiting for the host to let you in' })).toBeVisible();

  // Host admits from moderation panel (polls /waiting every 3 s)
  await openPanel(host, /^Host controls/);
  await host.getByRole('button', { name: `Admit ${GUEST}` }).click();

  await expect(guest.getByRole('contentinfo', { name: 'Meeting controls' })).toBeVisible({ timeout: 30_000 });
  await expectParticipantCount(host, 2);
  await expectParticipantCount(guest, 2);
  await expectRemoteVideo(host);
  await expectRemoteVideo(guest);
});

test('3. chat both ways, raise hand, reactions; 503s surface cleanly', async () => {
  const fromHost = `Hello from host ${Date.now()}`;
  const fromGuest = `Hi host, guest here ${Date.now()}`;

  await openPanel(host, /^Chat/);
  await host.getByLabel('Message', { exact: true }).fill(fromHost);
  await host.getByRole('button', { name: 'Send message' }).click();
  await expect(host.getByRole('log', { name: 'Chat messages' }).getByText(fromHost)).toBeVisible();

  // Guest sees unread badge, then the message
  await expect(guest.getByRole('button', { name: /^Chat, 1 unread/ })).toBeVisible();
  await openPanel(guest, /^Chat/);
  await expect(guest.getByRole('log', { name: 'Chat messages' }).getByText(fromHost)).toBeVisible();
  await guest.getByLabel('Message', { exact: true }).fill(fromGuest);
  await guest.getByRole('button', { name: 'Send message' }).click();
  await expect(host.getByRole('log', { name: 'Chat messages' }).getByText(fromGuest)).toBeVisible();
  await expect(guest.getByRole('log', { name: 'Chat messages' }).getByText(fromGuest)).toHaveCount(1);

  // File sharing. The local stack (start-local.ps1, "dev" profile) stores files on this PC's disk
  // (app.storage.type=local, D:/meetly/meet-files), so the host's upload succeeds and the guest sees the file.
  await openPanel(host, /^Chat/);
  await host.getByRole('tab', { name: 'Files' }).click();
  await host.getByLabel('Choose a file to share').setInputFiles({ name: 'agenda.txt', mimeType: 'text/plain', buffer: Buffer.from('agenda') });
  await expect(toast(host, 'File shared')).toBeVisible();
  await guest.getByRole('tab', { name: 'Files' }).click();
  await expect(guest.getByRole('button', { name: 'Download agenda.txt' })).toBeVisible({ timeout: 20_000 });
  // Guests (not signed in) may not share files (403 "File sharing is not available to guests."): refused, and told.
  await guest.getByLabel('Choose a file to share').setInputFiles({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('hello') });
  await expect(toast(guest, /not available to guests/)).toBeVisible();
  await expect(guest.getByRole('button', { name: 'Download notes.txt' })).toHaveCount(0);

  // Raise hand (guest) → host toast + hand icon in participants list
  await guest.getByRole('button', { name: 'Raise hand' }).click();
  await expect(guest.getByRole('button', { name: 'Lower hand' })).toBeVisible();
  await expect(toast(host, `${GUEST} raised their hand`)).toBeVisible();
  await openPanel(host, /^Participants$/);
  await expect(host.getByLabel('Hand raised')).toBeVisible();
  await guest.getByRole('button', { name: 'Lower hand' }).click();
  await expect(host.getByLabel('Hand raised')).toHaveCount(0);

  // Reaction (host → guest)
  await host.getByRole('button', { name: 'Send a reaction' }).click();
  await host.getByRole('button', { name: 'React 🎉' }).click();
  await expect(guest.locator('.animate-float-up', { hasText: '🎉' }).first()).toBeAttached();

  // Cloud recording: no egress → 503 expected, shown as an error toast; indicator must NOT stay on
  await openPanel(host, /^Host controls/);
  await host.getByRole('button', { name: 'Start cloud recording' }).click();
  await expect(host.locator('[data-sonner-toast][data-type="error"]').filter({ hasText: /media server|recording|unavailable/i }).last()).toBeVisible();
  test.info().annotations.push({ type: 'cloud-recording-toast', description: (await host.locator('[data-sonner-toast][data-type="error"]').last().textContent()) ?? '' });
  await expect(host.getByRole('button', { name: 'Start cloud recording' })).toBeVisible();
});

test('4. host mutes guest; request-unmute needs consent and never auto-unmutes', async () => {
  // Make sure the guest mic is on first
  await expect(guest.getByRole('button', { name: 'Mute microphone' })).toBeVisible();

  await participantAction('Mute microphone');
  await expect(guest.getByRole('button', { name: 'Unmute microphone' })).toBeVisible();
  await expect(host.getByRole('listitem').filter({ hasText: GUEST }).getByLabel('Microphone muted')).toBeVisible();

  await participantAction('Ask to unmute');
  const dialog = guest.getByRole('dialog', { name: /asking you to unmute your microphone/ });
  await expect(dialog).toBeVisible();
  // Nothing changes on its own (CSS locator: the modal hides the control bar from the a11y tree)
  await guest.waitForTimeout(4000);
  await expect(guest.locator('button[aria-label="Unmute microphone"]')).toBeVisible();
  await expect(host.getByRole('listitem').filter({ hasText: GUEST }).getByLabel('Microphone muted')).toBeVisible();

  await dialog.getByRole('button', { name: 'Unmute', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(guest.getByRole('button', { name: 'Mute microphone' })).toBeVisible();
  await expect(host.getByRole('listitem').filter({ hasText: GUEST }).getByLabel('Microphone on')).toBeVisible();

  // Declining keeps the guest muted
  await participantAction('Mute microphone');
  await expect(guest.getByRole('button', { name: 'Unmute microphone' })).toBeVisible();
  await participantAction('Ask to unmute');
  await guest.getByRole('dialog').getByRole('button', { name: 'Stay muted' }).click();
  await guest.waitForTimeout(2000);
  await expect(guest.getByRole('button', { name: 'Unmute microphone' })).toBeVisible();
});

test('6. screen share (fake media)', async () => {
  await host.getByRole('button', { name: 'Share screen' }).click();
  const started = host.getByRole('button', { name: 'Stop sharing screen' });
  const failed = host.locator('[data-sonner-toast][data-type="error"]').filter({ hasText: /screen sharing/i });
  await expect(started.or(failed).first()).toBeVisible({ timeout: 20_000 });
  if (await started.isVisible()) {
    test.info().annotations.push({ type: 'screen-share', description: 'started' });
    await guest.waitForFunction(
      () =>
        Array.from(document.querySelectorAll<HTMLVideoElement>('[data-lk-source="screen_share"] video')).some((v) => {
          const s = v.srcObject as MediaStream | null;
          return !!s && s.getVideoTracks().some((t) => t.readyState === 'live');
        }),
      undefined,
      { timeout: 30_000 },
    );
    test.info().annotations.push({ type: 'screen-share', description: 'guest received screen share track' });
    await started.click();
    await expect(host.getByRole('button', { name: 'Share screen' })).toBeVisible();
  } else {
    test.info().annotations.push({ type: 'screen-share', description: `failed: ${await failed.textContent()}` });
  }
});

test('5. lock blocks new joiners; removed guest sees removed screen and cannot rejoin', async ({ browser }) => {
  await openPanel(host, /^Host controls/);
  await host.getByRole('switch', { name: 'Lock meeting' }).click();
  await expect(toast(host, 'Meeting locked')).toBeVisible();
  await expect(host.getByText('Locked', { exact: true })).toBeVisible();

  const thirdCtx = await newContext(browser);
  const third = await thirdCtx.newPage();
  watch(third, 'third');
  await third.goto(`/j/${joinCode}`);
  await third.getByLabel('Your name').fill('Locked Out Lou');
  await third.getByRole('button', { name: 'Join meeting' }).click();
  const lockedAlert = third.getByRole('alert').filter({ hasText: /lock/i });
  await expect(lockedAlert).toBeVisible();
  test.info().annotations.push({ type: 'locked-message', description: (await lockedAlert.textContent()) ?? '' });
  await thirdCtx.close();

  // Remove guest
  await participantAction('Remove from meeting');
  await host.getByRole('alertdialog').getByRole('button', { name: 'Remove', exact: true }).click();
  // The dialog closes only once the remove request has returned; a hang here points at a slow server call.
  await expect(host.getByRole('alertdialog')).toBeHidden();
  await expect(guest.getByRole('heading', { name: 'You were removed from the meeting' })).toBeVisible();
  await expectParticipantCount(host, 1);

  // Rejoin attempt while locked → refused
  await guest.goto(`/j/${joinCode}`);
  await guest.getByLabel('Your name').fill(GUEST);
  await guest.getByRole('button', { name: 'Join meeting' }).click();
  await expect(guest.getByRole('alert').filter({ hasText: /.+/ }).first()).toBeVisible();
  test.info().annotations.push({ type: 'rejoin-while-locked', description: (await guest.getByRole('alert').first().textContent()) ?? '' });

  // Unlock; a removed guest re-joining (new anonymous participant) is recorded, not asserted
  await openPanel(host, /^Host controls/);
  await host.getByRole('switch', { name: 'Lock meeting' }).click();
  await expect(toast(host, 'Meeting unlocked')).toBeVisible();
  await guest.getByRole('button', { name: 'Join meeting' }).click();
  const waiting = guest.getByRole('heading', { name: /Waiting for the host/ });
  const refused = guest.getByRole('alert').filter({ hasText: /.+/ }).first();
  await expect(waiting.or(refused)).toBeVisible();
  test.info().annotations.push({
    type: 'rejoin-after-unlock',
    description: (await waiting.isVisible()) ? 'guest placed in waiting room again (new anonymous participant)' : `refused: ${await refused.textContent()}`,
  });
  if (await waiting.isVisible()) {
    await openPanel(host, /^Host controls/);
    await host.getByRole('button', { name: `Deny ${GUEST}` }).click();
    await expect(guest.getByRole('heading', { name: "You weren't admitted" })).toBeVisible();
  }
});

test('7. leave vs end-for-all; ended screen; history shows ENDED with participants', async ({ browser }) => {
  // A late participant joins (admitted) so that "others" see the end
  const lateCtx = await newContext(browser);
  const late = await lateCtx.newPage();
  watch(late, 'late');
  await late.goto(`/j/${joinCode}`);
  await late.getByLabel('Your name').fill(LATE);
  await late.getByRole('button', { name: 'Join meeting' }).click();
  await expect(late.getByRole('heading', { name: /Waiting for the host/ })).toBeVisible();
  await openPanel(host, /^Host controls/);
  await host.getByRole('button', { name: `Admit ${LATE}` }).click();
  await expect(late.getByRole('contentinfo', { name: 'Meeting controls' })).toBeVisible({ timeout: 30_000 });
  await expectParticipantCount(late, 2);

  // Participant's leave dialog: no "end for everyone"
  await late.getByRole('button', { name: 'Leave meeting' }).first().click();
  const lateDialog = late.getByRole('dialog', { name: 'Leave the meeting?' });
  await expect(lateDialog).toBeVisible();
  await expect(lateDialog.getByRole('button', { name: 'End for everyone' })).toHaveCount(0);
  await lateDialog.getByRole('button', { name: 'Cancel' }).click();

  // Host: leave vs end dialog → end for everyone
  await host.getByRole('contentinfo', { name: 'Meeting controls' }).getByRole('button', { name: 'Leave meeting' }).click();
  const hostDialog = host.getByRole('dialog', { name: 'Leave or end the meeting?' });
  await expect(hostDialog.getByRole('button', { name: 'Leave meeting' })).toBeVisible();
  await hostDialog.getByRole('button', { name: 'End for everyone' }).click();
  await expect(host.getByRole('heading', { name: 'The meeting has ended' })).toBeVisible();
  await expect(late.getByRole('heading', { name: 'The meeting has ended' })).toBeVisible({ timeout: 30_000 });
  await lateCtx.close();

  // History
  await host.goto('/meetings?tab=past');
  const card = host.locator('div').filter({ has: host.getByRole('link', { name: title }) }).last();
  await expect(card.getByText('Ended')).toBeVisible();
  await host.getByRole('link', { name: title }).click();
  await expect(host.getByRole('heading', { name: title })).toBeVisible();
  const table = host.getByRole('table');
  await expect(table.getByRole('cell', { name: GUEST }).first()).toBeVisible();
  await expect(table.getByRole('cell', { name: LATE }).first()).toBeVisible();
  await expect(table.getByRole('cell', { name: 'Host Hannah' }).first()).toBeVisible();
});
