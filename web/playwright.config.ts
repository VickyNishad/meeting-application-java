import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';
const live = process.env.E2E_LIVE === '1';

/** Chromium flags: fake camera/mic (auto-granted) and auto-pick a capture source for getDisplayMedia. */
const mediaArgs = [
  '--use-fake-ui-for-media-stream',
  '--use-fake-device-for-media-stream',
  '--auto-select-desktop-capture-source=Entire screen',
  '--auto-accept-this-tab-capture',
  '--autoplay-policy=no-user-gesture-required',
];

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    permissions: ['camera', 'microphone'],
    launchOptions: { args: mediaArgs },
  },
  projects: [
    { name: 'chromium', testIgnore: /live\//, use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', testIgnore: /live\//, use: { ...devices['Pixel 7'] } },
    // Live end-to-end suite against the running local stack (E2E_LIVE=1).
    {
      name: 'live',
      testMatch: /live\/.*\.spec\.ts/,
      timeout: 180_000,
      expect: { timeout: 20_000 },
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1400, height: 900 },
        timezoneId: 'UTC',
        locale: 'en-US',
        actionTimeout: 20_000,
        navigationTimeout: 30_000,
        trace: 'retain-on-failure',
        video: 'retain-on-failure',
        launchOptions: { args: mediaArgs },
      },
    },
  ],
  webServer:
    process.env.E2E_BASE_URL || live
      ? undefined
      : {
          command: 'npm run dev -- --port 5173 --strictPort',
          url: baseURL,
          reuseExistingServer: true,
          timeout: 120_000,
        },
});
