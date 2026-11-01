import { expect, test } from '@playwright/test';
import { flushDiagnostics, liveOnly, registerAndLogin, registerViaApi, toast, watch } from './helpers';

liveOnly();

test.afterEach(async ({}, testInfo) => {
  await flushDiagnostics(testInfo);
});

test('9. settings, calendar card, recordings, organizations, notifications, dashboard load cleanly', async ({ page }) => {
  const d = watch(page, 'user');
  const user = await registerAndLogin(page, 'Pages Pat');

  // Dashboard: personal room card
  await expect(page.getByText('My personal room')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Copy personal room link' })).toBeVisible();
  await expect(page.getByText('No meetings are live right now.')).toBeVisible();

  // Notifications dropdown
  await page.getByRole('button', { name: /^Notifications/ }).click();
  await expect(page.getByRole('heading', { name: 'Notifications' })).toBeVisible();
  await expect(page.getByText("You're all caught up.").or(page.getByRole('button', { name: /.+/ }).filter({ hasText: /ago|now/ }).first())).toBeVisible();
  await page.keyboard.press('Escape');

  // Settings
  await page.goto('/settings');
  await expect(page.getByRole('heading', { name: 'Profile' })).toBeVisible();
  await page.getByLabel('Display name').fill('Pages Pat Renamed');
  await page.getByRole('button', { name: 'Save profile' }).click();
  await expect(toast(page, 'Profile updated')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Calendar integrations' })).toBeVisible();
  const connectButtons = page.getByRole('button', { name: /^Connect / });
  await expect(connectButtons.first()).toBeVisible();
  for (const b of await connectButtons.all()) {
    const label = (await b.getAttribute('aria-label')) ?? '';
    if (label.includes('not configured')) await expect(b).toBeDisabled();
  }
  await expect(page.getByText('This device')).toBeVisible();
  // Wrong current password → API error mapped into the form
  await page.getByLabel('Current password').fill('not-my-password-123');
  await page.getByLabel('New password', { exact: true }).fill('Another-Horse-99!');
  await page.getByLabel('Confirm new password').fill('Another-Horse-99!');
  await page.getByRole('button', { name: 'Change password' }).click();
  await expect(page.getByRole('alert').first()).toBeVisible();

  // Calendar return toast
  await page.goto('/settings?calendar=error');
  await expect(toast(page, 'could not be connected')).toBeVisible();
  await expect(page).toHaveURL(/\/settings$/);

  // Recordings: empty state
  await page.goto('/recordings');
  await expect(page.getByRole('heading', { name: 'No recordings yet' })).toBeVisible();

  // Meetings list tabs
  await page.goto('/meetings');
  await expect(page.getByRole('heading', { name: 'No upcoming meetings' }).or(page.getByRole('link').filter({ hasText: /.+/ }).first())).toBeVisible();
  await page.getByRole('tab', { name: 'Past' }).click();
  await expect(page).toHaveURL(/tab=past/);

  // Organizations: create + add member + change role
  const member = await registerViaApi(page, 'Member Max');
  await page.goto('/organizations');
  await expect(page.getByRole('heading', { name: "You're not in an organization" })).toBeVisible();
  await page.getByRole('button', { name: 'New organization' }).click();
  const dialog = page.getByRole('dialog', { name: 'Create organization' });
  const orgName = `E2E Org ${Date.now()}`;
  await dialog.getByLabel('Name').fill(orgName);
  await expect(dialog.getByLabel('Slug')).toHaveValue(/^e2e-org-\d+$/);
  await dialog.getByRole('button', { name: 'Create' }).click();
  await expect(toast(page, 'Organization created')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'General' })).toBeVisible();
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue(orgName);
  await page.getByLabel('Email').fill(member.email);
  await page.getByRole('button', { name: 'Add' }).click();
  await expect(toast(page, 'Member added')).toBeVisible();
  const memberRole = page.getByRole('combobox', { name: 'Role of Member Max' });
  await expect(memberRole).toHaveValue('MEMBER');
  await memberRole.selectOption('ORGANIZATION_ADMIN');
  await expect(toast(page, 'Role updated')).toBeVisible();
  // Adding an unknown email surfaces the API error
  await page.getByLabel('Email').fill(`nobody.${Date.now()}@e2e.test`);
  await page.getByRole('button', { name: 'Add' }).click();
  await expect(page.getByRole('alert').filter({ hasText: /.+/ }).first()).toBeVisible();
  // Org settings save (optimistic version)
  await page.getByLabel('Default recording retention (days)').fill('45');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(toast(page, 'Organization updated')).toBeVisible();

  // Scheduling pages load
  await page.goto('/scheduling/bookings');
  await expect(page.getByRole('heading', { name: 'No upcoming bookings' })).toBeVisible();
  await page.goto('/scheduling/pages');
  await expect(page.getByRole('heading', { name: 'No booking pages yet' })).toBeVisible();

  // Admin is gated for normal users
  await page.goto('/admin');
  await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();

  expect(d.pageErrors, 'uncaught page errors').toEqual([]);
  const unexpectedConsole = d.consoleErrors.filter((c) => !c.startsWith('Failed to load resource'));
  expect(unexpectedConsole, 'console errors').toEqual([]);
  void user;
});
