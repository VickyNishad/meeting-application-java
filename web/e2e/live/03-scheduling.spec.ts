import { expect, test, type Page } from '@playwright/test';
import { flushDiagnostics, liveOnly, newContext, registerAndLogin, toast, watch } from './helpers';

liveOnly();

test.afterEach(async ({}, testInfo) => {
  await flushDiagnostics(testInfo);
});

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

async function setOpenAvailability(host: Page) {
  await host.goto('/scheduling/availability');
  await expect(host.getByRole('heading', { name: 'Weekly hours' })).toBeVisible();
  await host.getByLabel('Time zone').first().selectOption('UTC');
  for (const day of DAYS) {
    const sw = host.getByRole('switch', { name: day, exact: true });
    if ((await sw.getAttribute('aria-checked')) !== 'true') await sw.click();
    await host.getByLabel(`${day} interval 1 start`).fill('06:00');
    await host.getByLabel(`${day} interval 1 end`).fill('22:00');
  }
  await host.getByRole('button', { name: 'Save weekly hours' }).click();
  await expect(toast(host, 'Availability saved')).toBeVisible();
  // Reload shows persisted values (server returns HH:mm:ss)
  await host.reload();
  await expect(host.getByLabel('Monday interval 1 start')).toHaveValue('06:00');
  await expect(host.getByLabel('Sunday interval 1 end')).toHaveValue('22:00');
}

async function createBookingPage(host: Page, slug: string, title: string) {
  await host.goto('/scheduling/pages');
  await host.getByRole('button', { name: /New booking page|Create booking page/ }).first().click();
  const dialog = host.getByRole('dialog', { name: 'New booking page' });
  await dialog.getByLabel('Title').fill(title);
  await dialog.getByLabel('URL slug').fill(slug);
  await dialog.getByLabel('Duration (minutes)').fill('30');
  await dialog.getByLabel('Minimum notice (minutes)').fill('60');
  await dialog.getByRole('button', { name: 'Create' }).click();
  await expect(toast(host, 'Booking page created')).toBeVisible();
  await expect(host.getByText(`/book/${slug}`)).toBeVisible();
}

async function pickFirstSlot(page: Page): Promise<string> {
  const first = page.locator('section[aria-labelledby^="day-"] ul button').first();
  await expect(first).toBeVisible({ timeout: 30_000 });
  const label = (await first.textContent())!.trim();
  await first.click();
  return label;
}

async function fillAndConfirm(page: Page, name: string, email: string) {
  await page.getByLabel('Name').fill(name);
  await page.getByLabel('Email').fill(email);
  await page.getByRole('button', { name: 'Confirm booking' }).click();
}

test('8. availability → booking page → public booking → manage → reschedule → cancel; no double booking', async ({ page: host, browser }) => {
  watch(host, 'host');
  await registerAndLogin(host, 'Scheduler Sam');
  await setOpenAvailability(host);

  // Date override (exception) round-trip
  await host.getByLabel('Reason (optional)').fill('Dentist');
  await host.getByRole('button', { name: 'Add override' }).click();
  await expect(toast(host, 'Date override added')).toBeVisible();
  await expect(host.getByText('Dentist')).toBeVisible();
  await host.getByRole('button', { name: /Delete override on/ }).first().click();
  await host.getByRole('alertdialog').getByRole('button', { name: 'Delete' }).click();
  await expect(host.getByText('Dentist')).toHaveCount(0);

  const slug = `e2e-${Date.now().toString(36)}`;
  await createBookingPage(host, slug, 'Intro call');

  // Guest books
  const guestCtx = await newContext(browser);
  const guest = await guestCtx.newPage();
  watch(guest, 'guest');
  await guest.goto(`/book/${slug}`);
  await expect(guest.getByRole('heading', { name: 'Intro call' })).toBeVisible();
  await expect(guest.getByLabel('Time zone')).toHaveValue('UTC');
  await pickFirstSlot(guest);
  await fillAndConfirm(guest, 'Booker Bea', `bea.${Date.now()}@e2e.test`);
  await expect(guest.getByRole('heading', { name: "You're booked!" })).toBeVisible();
  const manageUrl = (await guest.locator('code').filter({ hasText: '/bookings/manage/' }).textContent())!.trim();
  expect(manageUrl).toContain('/bookings/manage/');

  // Manage → reschedule
  await guest.getByRole('link', { name: 'Open' }).click();
  await expect(guest.getByText('Your booking')).toBeVisible();
  await expect(guest.getByText('confirmed')).toBeVisible();
  const before = (await guest.locator('p.font-medium').first().textContent())!.trim();
  await guest.getByRole('button', { name: 'Reschedule' }).click();
  await pickFirstSlot(guest);
  await guest.getByRole('button', { name: /^Move to / }).click();
  await expect(toast(guest, 'Booking rescheduled')).toBeVisible();
  if (await guest.getByRole('heading', { name: "You're booked!" }).isVisible()) {
    test.info().annotations.push({ type: 'reschedule', description: 'new manage token issued → confirmation page' });
    await guest.getByRole('link', { name: 'Open' }).click();
  } else {
    test.info().annotations.push({ type: 'reschedule', description: 'same manage token' });
  }
  await expect(guest.getByText('Your booking')).toBeVisible();
  const after = (await guest.locator('p.font-medium').first().textContent())!.trim();
  expect(after).not.toBe(before);

  // Cancel
  await guest.getByRole('button', { name: 'Cancel booking' }).click();
  await guest.getByRole('alertdialog').getByRole('button', { name: 'Cancel booking' }).click();
  await expect(toast(guest, 'Booking cancelled')).toBeVisible();
  await expect(guest.getByText('cancelled', { exact: true })).toBeVisible();
  await expect(guest.getByRole('button', { name: 'Reschedule' })).toHaveCount(0);

  // Host sees bookings list
  await host.goto('/scheduling/bookings');
  await expect(host.getByText('Booker Bea').first()).toBeVisible();

  // Double booking: two invitees hold the same slot; second must fail cleanly
  const aCtx = await newContext(browser);
  const bCtx = await newContext(browser);
  const a = await aCtx.newPage();
  const b = await bCtx.newPage();
  watch(a, 'invitee-A');
  watch(b, 'invitee-B');
  await a.goto(`/book/${slug}`);
  await b.goto(`/book/${slug}`);
  const label = await pickFirstSlot(a);
  const bFirst = b.locator('section[aria-labelledby^="day-"] ul button').first();
  await expect(bFirst).toHaveText(label);
  await bFirst.click();
  await a.getByLabel('Name').fill('Invitee A');
  await a.getByLabel('Email').fill(`a.${Date.now()}@e2e.test`);
  await b.getByLabel('Name').fill('Invitee B');
  await b.getByLabel('Email').fill(`b.${Date.now()}@e2e.test`);
  await a.getByRole('button', { name: 'Confirm booking' }).click();
  await expect(a.getByRole('heading', { name: "You're booked!" })).toBeVisible();
  await b.getByRole('button', { name: 'Confirm booking' }).click();
  await expect(b.getByRole('alert').filter({ hasText: /no longer available/i })).toBeVisible();
  await expect(b.getByRole('heading', { name: "You're booked!" })).toHaveCount(0);
  // The taken slot is gone from B's refreshed list
  await expect(b.locator('section[aria-labelledby^="day-"] ul button').first()).not.toHaveText(label);

  await Promise.all([guestCtx.close(), aCtx.close(), bCtx.close()]);
});
