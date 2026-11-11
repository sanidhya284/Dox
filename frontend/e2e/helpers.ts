import { expect, type Browser, type Page } from '@playwright/test';

/** Each call gets its own browser context (its own cookie jar), like a separate person on their own device. */
export async function freshPage(browser: Browser, viewport?: { width: number; height: number }): Promise<Page> {
  const ctx = await browser.newContext(viewport ? { viewport, isMobile: viewport.width < 640, hasTouch: true } : {});
  return ctx.newPage();
}

let n = 0;
/** A username unique per run: e2e + time + counter (lower case, 3..50 chars). */
export function uniqueName(prefix: string): string {
  n += 1;
  return `${prefix}${Date.now().toString(36)}${n}`;
}

export const PASSWORD = 'e2e-password-123';

/** Signs up through the UI and waits for the app (connected). */
export async function signUp(page: Page, username: string, displayName: string): Promise<void> {
  await page.goto('/');
  await page.getByLabel('username').fill(username);
  await page.getByLabel('display name').fill(displayName);
  await page.getByLabel('email').fill(`${username}@example.com`);
  await page.getByLabel('password').fill(PASSWORD);
  await page.getByRole('button', { name: '[ create account ]' }).click();
  await expect(page.getByText(`${username}@dox`)).toBeVisible({ timeout: 20_000 });
}

/** Logs in through the UI (the Log in tab). */
export async function logIn(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/');
  await page.getByRole('tab', { name: 'log in' }).click();
  await page.getByLabel('username').fill(username);
  await page.getByLabel('password').fill(password);
  await page.getByRole('button', { name: '[ log in ]' }).click();
  await expect(page.getByText(`${username}@dox`)).toBeVisible({ timeout: 20_000 });
}

export async function connected(page: Page): Promise<void> {
  await expect(page.getByText('● connected')).toBeVisible({ timeout: 20_000 });
}

export async function openChannel(page: Page, label: string): Promise<void> {
  await page.locator('.chan-list').getByText(label, { exact: true }).click({ timeout: 20_000 });
  await expect(page.locator('.topbar h1')).toContainText(label);
}

export async function say(page: Page, text: string): Promise<void> {
  const box = page.getByRole('textbox', { name: /^Message / });
  await box.fill(text);
  await box.press('Enter');
}
