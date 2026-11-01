import { expect, test } from '@playwright/test';
import { PASSWORD, flushDiagnostics, liveOnly, login, registerAndLogin, watch } from './helpers';

liveOnly();

test.afterEach(async ({}, testInfo) => {
  await flushDiagnostics(testInfo);
});

test('auth: register, login, logout, wrong password, forgot password, protected redirect', async ({ page }) => {
  watch(page, 'auth');

  // Protected route redirect while signed out
  await page.goto('/meetings');
  await expect(page).toHaveURL(/\/login\?next=%2Fmeetings/);

  // Registration + login
  const user = await registerAndLogin(page, 'Auth Tester');
  await expect(page.getByRole('heading', { name: /Hi, Auth/ })).toBeVisible();

  // Session survives a reload (refresh cookie → in-memory access token)
  await page.reload();
  await expect(page.getByRole('heading', { name: /Hi, Auth/ })).toBeVisible();

  // Logout
  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.goto('/dashboard');
  await expect(page).toHaveURL(/\/login\?next=%2Fdashboard/);

  // Wrong password shows the API error
  await page.getByLabel('Email').fill(user.email);
  await page.getByLabel('Password', { exact: true }).fill('definitely-wrong-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert').filter({ hasText: /.+/ }).first()).toBeVisible();
  await expect(page).toHaveURL(/\/login/);

  // Correct password honours ?next
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/dashboard/);

  // Duplicate registration error (EMAIL_ALREADY_REGISTERED) surfaces cleanly
  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  // Wait for logout to finish: navigating earlier aborts the request and the refresh cookie survives.
  await expect(page).toHaveURL(/\/login/);
  await page.goto('/register');
  await page.getByLabel('Full name').fill('Dup');
  await page.getByLabel('Email').fill(user.email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Confirm password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('alert').first()).toBeVisible();

  // Forgot password → 202 → neutral message
  await page.goto('/forgot-password');
  await page.getByLabel('Email').fill(user.email);
  await page.getByRole('button', { name: 'Send reset link' }).click();
  await expect(page.getByText(/If an account exists for/)).toBeVisible();

  // Reset page without token
  await page.goto('/reset-password');
  await expect(page.getByText(/missing its token/)).toBeVisible();

  // Login again from scratch
  await page.goto('/login');
  await login(page, user.email, PASSWORD);
});
