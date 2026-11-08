import { expect, test, type Browser, type Page } from '@playwright/test';

const PASSWORD = process.env.DEMO_PASSWORD ?? 'local-demo-password';

async function loggedIn(browser: Browser, username: string): Promise<Page> {
  const page = await (await browser.newContext()).newPage(); // its own cookie jar
  await page.goto('/');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Log in', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('Connected', { timeout: 20_000 });
  await page.getByRole('button', { name: /Demo room/ }).click();
  return page;
}

test('a message demo1 sends appears live for demo2, as text', async ({ browser }) => {
  const alice = await loggedIn(browser, 'demo1');
  const bob = await loggedIn(browser, 'demo2');

  const text = `e2e ${Date.now()} <b>not bold</b>`;
  await alice.getByLabel('Message', { exact: true }).fill(text);
  await alice.getByRole('button', { name: 'Send' }).click();

  await expect(bob.getByText(text)).toBeVisible({ timeout: 20_000 }); // pushed over the WebSocket
  await expect(alice.getByText(text)).toBeVisible(); // the sender's own copy came from the ack
  await expect(bob.locator('.messages b')).toHaveCount(0); // rendered as text, not HTML
});
