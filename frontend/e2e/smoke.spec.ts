import { expect, test } from '@playwright/test';
import { connected, freshPage, logIn, openChannel, say } from './helpers';

const PASSWORD = process.env.DEMO_PASSWORD ?? 'local-demo-password';

test('a message demo1 sends appears live for demo2, as text', async ({ browser }) => {
  const alice = await freshPage(browser);
  const bob = await freshPage(browser);
  for (const [page, user] of [[alice, 'demo1'], [bob, 'demo2']] as const) {
    await logIn(page, user, PASSWORD);
    await openChannel(page, '#demo-room');
    await connected(page);
  }

  const text = `e2e ${Date.now()} <b>not bold</b>`;
  await say(alice, text);

  await expect(bob.locator('.log').getByText(text)).toBeVisible({ timeout: 20_000 }); // pushed over the WebSocket
  await expect(alice.locator('.log').getByText(text)).toBeVisible(); // the sender's own copy came from the ack
  await expect(bob.locator('.log b')).toHaveCount(0); // rendered as text, not HTML
});
