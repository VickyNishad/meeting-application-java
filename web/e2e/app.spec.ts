import { expect, test, type APIRequestContext } from '@playwright/test';

/**
 * UI smoke tests run against the Vite dev server (no backend needed: /auth/refresh fails → anonymous).
 * The backend flow runs only when the API is reachable (set E2E_USER_EMAIL / E2E_USER_PASSWORD for a
 * verified account); otherwise it is skipped.
 */

async function backendUp(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get('/api/v1/auth/oauth2/providers', { timeout: 3000 });
    return res.ok();
  } catch {
    return false;
  }
}

test.describe('public UI', () => {
  test('unauthenticated users are redirected to sign in', async ({ page }) => {
    await page.goto('/dashboard');
    await expect(page).toHaveURL(/\/login\?next=%2Fdashboard/);
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    await expect(page.getByLabel('Email')).toBeVisible();
  });

  test('login form validates input client-side', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByText('Enter a valid email address')).toBeVisible();
  });

  test('theme toggle switches to dark mode', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: /change theme/i }).click();
    await page.getByRole('menuitemradio', { name: 'Dark' }).click();
    await expect(page.locator('html')).toHaveClass(/dark/);
  });
});

test.describe('with backend', () => {
  test.beforeEach(async ({ request }) => {
    test.skip(!(await backendUp(request)), 'Backend API is not reachable');
    test.skip(!process.env.E2E_USER_EMAIL || !process.env.E2E_USER_PASSWORD, 'E2E_USER_EMAIL / E2E_USER_PASSWORD not set');
  });

  test('sign in, schedule a meeting and view its details', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('Email').fill(process.env.E2E_USER_EMAIL!);
    await page.getByLabel('Password', { exact: true }).fill(process.env.E2E_USER_PASSWORD!);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/dashboard/);

    await page.goto('/meetings/new');
    const title = `E2E meeting ${Date.now()}`;
    await page.getByLabel('Title').fill(title);
    await page.getByRole('button', { name: 'Schedule meeting' }).click();
    await expect(page.getByRole('heading', { name: title })).toBeVisible();
    await expect(page.getByRole('button', { name: /copy join link/i })).toBeVisible();
  });
});
