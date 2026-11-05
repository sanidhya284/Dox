import { defineConfig } from '@playwright/test';

/**
 * One end-to-end smoke test against the running compose stack (deploy/compose.yml), not a dev server.
 * At most ONE retry; a retried pass is reported as "flaky" in test-results/e2e.json, never hidden.
 */
export default defineConfig({
  testDir: 'e2e',
  retries: 1,
  workers: 1,
  timeout: 60_000,
  reporter: [['list'], ['json', { outputFile: 'test-results/e2e.json' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'https://localhost',
    ignoreHTTPSErrors: true, // local stack: Caddy's local CA
    trace: 'retain-on-failure',
  },
});
