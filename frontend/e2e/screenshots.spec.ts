import { test, type APIRequestContext, type Browser } from '@playwright/test';
import { randomUUID } from 'node:crypto';

/**
 * README screenshots (docs/screenshots/), generated against the local compose stack started with
 * deploy/compose.e2e.yml. Skipped unless SCREENSHOTS=1, so `npm run e2e` and CI never run it:
 *   SCREENSHOTS=1 npx playwright test e2e/screenshots.spec.ts
 * It seeds three local demo-style accounts and one small conversation (only if they don't exist yet).
 */
test.skip(!process.env.SCREENSHOTS, 'set SCREENSHOTS=1 to regenerate the README screenshots');

const OUT = '../docs/screenshots';
// Local stack only. Override if the accounts already exist with another password on your stack.
const PASSWORD = process.env.SCREENSHOT_PASSWORD ?? 'screenshot-password-123';
const PEOPLE: [string, string][] = [['mira', 'Mira Shah'], ['theo', 'Theo Park'], ['jun', 'Jun Ito']];

async function account(api: APIRequestContext, username: string, displayName: string) {
  let r = await api.post('/api/auth/login', { data: { username, password: PASSWORD } });
  if (!r.ok()) {
    r = await api.post('/api/auth/register', {
      data: { username, displayName, email: `${username}@example.com`, password: PASSWORD },
    });
    if (!r.ok()) {
      throw new Error(`${username}: ${r.status()} ${await r.text()}`);
    }
  }
  const token = (await r.json()).accessToken as string;
  return { token, id: JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString()).sub as string };
}

async function seed(api: APIRequestContext): Promise<string> {
  const u: Record<string, { token: string; id: string }> = {};
  for (const [name, display] of PEOPLE) {
    u[name] = await account(api, name, display);
  }
  const call = async (who: string, method: string, path: string, data?: unknown) => {
    const r = await api.fetch(path, { method, data, headers: { Authorization: `Bearer ${u[who].token}` } });
    if (!r.ok()) {
      throw new Error(`${method} ${path}: ${r.status()}`);
    }
    const t = await r.text();
    return t ? JSON.parse(t) : null;
  };
  const say = (who: string, conv: string, body: string) =>
    call(who, 'POST', `/api/conversations/${conv}/messages`, { clientMsgId: randomUUID(), body });

  const existing = (await call('mira', 'GET', '/api/conversations')).find((c: { title: string }) => c.title === 'Weekend plans');
  if (existing) {
    return existing.id;
  }
  const group = await call('mira', 'POST', '/api/conversations/group',
    { title: 'Weekend plans', memberIds: [u.theo.id, u.jun.id] });
  await say('theo', group.id, 'Saturday still on?');
  await say('mira', group.id, 'Yes! Meet at the trailhead at 8.');
  await say('jun', group.id, "I'll bring coffee ☕");
  await say('theo', group.id, 'Perfect. The forecast says clear skies all morning.');
  await say('mira', group.id, 'See you there 🥾');
  const last = (await call('mira', 'GET', `/api/conversations/${group.id}`)).lastSeq;
  await call('theo', 'POST', `/api/conversations/${group.id}/cursor`, { kind: 'read', seq: last });
  const dmBen = await call('mira', 'POST', '/api/conversations/direct', { otherUserId: u.theo.id });
  await say('theo', dmBen.id, 'Did you get the photos from last time?');
  await say('mira', dmBen.id, 'Got them, they look great!');
  const dmCy = await call('jun', 'POST', '/api/conversations/direct', { otherUserId: u.mira.id });
  await say('jun', dmCy.id, 'Can you send me the trailhead address?');
  return group.id;
}

async function shot(browser: Browser, file: string, opts: { width: number; height: number; dark: boolean;
  hash?: string; scale: number; loggedIn?: boolean }) {
  const ctx = await browser.newContext({
    viewport: { width: opts.width, height: opts.height }, colorScheme: opts.dark ? 'dark' : 'light',
    deviceScaleFactor: opts.scale, isMobile: opts.width < 640, hasTouch: opts.width < 640,
  });
  if (opts.loggedIn !== false) {
    await ctx.request.post('/api/auth/login', { data: { username: 'mira', password: PASSWORD } });
  }
  const page = await ctx.newPage();
  await page.goto(`/${opts.hash ?? ''}`);
  await page.waitForSelector(opts.loggedIn === false ? '.auth .window' : '.log, .chan-list li');
  await page.waitForTimeout(2000); // socket connected, history and read receipts loaded
  // Local images say "build unknown"; hide it (through the DOM: the CSP rightly refuses injected styles).
  await page.evaluate(() => document.querySelector<HTMLElement>('.build-info')?.style.setProperty('display', 'none'));
  await page.screenshot({ path: `${OUT}/${file}` });
  await ctx.close();
}

test('README screenshots', async ({ browser, request }) => {
  test.setTimeout(120_000);
  const group = await seed(request);
  await shot(browser, 'desktop-dark.png', { width: 1280, height: 720, dark: true, hash: `#/c/${group}`, scale: 1 });
  await shot(browser, 'desktop-light.png', { width: 1280, height: 720, dark: false, hash: `#/c/${group}`, scale: 1 });
  await shot(browser, 'phone.png', { width: 390, height: 760, dark: true, hash: `#/c/${group}`, scale: 2 });
  await shot(browser, 'sign-in.png', { width: 1280, height: 720, dark: true, scale: 1, loggedIn: false });
});
