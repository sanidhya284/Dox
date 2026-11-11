import { expect, test } from '@playwright/test';
import { connected, freshPage, openChannel, say, signUp, uniqueName } from './helpers';

/**
 * Phase 6b end to end (ADR-0012), against the compose stack started with deploy/compose.e2e.yml (raised limits).
 * Each person is a separate browser context; every name is unique per run, so the tests never depend on data
 * left by an earlier run.
 */

test('sign up lands in the app, logged in, with an empty channel list', async ({ browser }) => {
  const page = await freshPage(browser);
  const name = uniqueName('su');
  await signUp(page, name, 'Sign Up');
  await expect(page.getByText('no conversations yet')).toBeVisible();
});

test('try the demo: a guest gets the bot, and the bot reads and answers', async ({ browser }) => {
  const page = await freshPage(browser);
  await page.goto('/');
  await page.getByRole('button', { name: '[ try the demo ]' }).click();
  await expect(page.locator('.guest-note')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('.chan-list')).toContainText('#your-demo-group');

  await openChannel(page, '@dox-bot');
  await connected(page);
  await say(page, 'hello bot');
  await expect(page.locator('.log').getByText(/I'm only a bot/)).toBeVisible({ timeout: 20_000 }); // the after-commit reply
  await expect(page.locator('.log').getByText(/✓✓ read/)).toBeVisible({ timeout: 20_000 }); // the bot's read cursor, live
});

test('a DM started by username search carries live messages both ways', async ({ browser }) => {
  const ana = await freshPage(browser);
  const ben = await freshPage(browser);
  const anaName = uniqueName('ana');
  const benName = uniqueName('ben');
  await signUp(ana, anaName, 'Ana E2E');
  await signUp(ben, benName, 'Ben E2E');

  await ana.getByRole('button', { name: '[+ dm]' }).click();
  await ana.getByLabel('to:').fill(benName);
  await ana.getByRole('button', { name: new RegExp(`@${benName}`) }).click();
  await connected(ana);
  await say(ana, 'ping from ana');

  // Ben's list learns about the new DM from the push and shows it; the message arrived live.
  await openChannel(ben, `@${anaName}`);
  await expect(ben.locator('.log').getByText('ping from ana')).toBeVisible({ timeout: 20_000 });
  await say(ben, 'pong from ben');
  await expect(ana.locator('.log').getByText('pong from ben')).toBeVisible({ timeout: 20_000 });
});

test('a group made with the member picker reaches every member', async ({ browser }) => {
  const [owner, m1, m2] = [await freshPage(browser), await freshPage(browser), await freshPage(browser)];
  const names = [uniqueName('own'), uniqueName('mem'), uniqueName('mem')];
  await signUp(owner, names[0], 'Owner');
  await signUp(m1, names[1], 'Member One');
  await signUp(m2, names[2], 'Member Two');

  await owner.getByRole('button', { name: '[+ group]' }).click();
  const title = `e2e ${names[0]}`;
  await owner.getByLabel('name:').fill(title);
  for (const member of [names[1], names[2]]) {
    await owner.getByLabel('add people:').fill(member);
    await owner.getByRole('button', { name: new RegExp(`@${member}`) }).click();
  }
  await expect(owner.locator('.chip')).toHaveCount(2);
  await owner.getByRole('button', { name: /create group with 2/ }).click();
  await connected(owner);
  await say(owner, 'hello group');

  const channel = `#${title.toLowerCase().replace(/\s+/g, '-')}`;
  for (const member of [m1, m2]) {
    await openChannel(member, channel);
    await expect(member.locator('.log').getByText('hello group')).toBeVisible({ timeout: 20_000 });
  }
});

test('phone: the channel list and the conversation are separate screens, with Back', async ({ browser }) => {
  const page = await freshPage(browser, { width: 390, height: 844 });
  await page.goto('/');
  await page.getByRole('button', { name: '[ try the demo ]' }).click();
  await expect(page.locator('.chan-list')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('.topbar')).toHaveCount(0); // list only

  await openChannel(page, '@dox-bot');
  await expect(page.locator('.chan-list')).toHaveCount(0); // conversation only
  await expect(page.locator('.log').getByText(/welcome to Dox/)).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

  await page.getByRole('button', { name: 'Back to conversations' }).click();
  await expect(page.locator('.chan-list')).toBeVisible();
  await expect(page.locator('.topbar')).toHaveCount(0);
});
