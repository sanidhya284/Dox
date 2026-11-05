import { describe, expect, it } from 'vitest';
import { AuthSession, type FetchLike, type LockManagerLike } from '../auth';
import { createApi } from '../http';

function jwt(sub: string): string {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${b64({ alg: 'HS256' })}.${b64({ sub })}.sig`;
}

/** A fake backend: every access token but the latest is expired; refresh mints a new one. */
function fakeBackend(opts: { refreshOk?: boolean } = {}) {
  let current = jwt('user-1');
  let n = 0;
  const calls: string[] = [];
  const fetchImpl: FetchLike = async (path, init) => {
    calls.push(path);
    const json = (status: number, body: unknown) =>
      new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
    if (path === '/api/auth/refresh') {
      await new Promise((r) => setTimeout(r, 5)); // concurrent callers overlap with this refresh
      if (opts.refreshOk === false) {
        return json(401, { code: 'TOKEN_INVALID' });
      }
      current = jwt(`user-1-${++n}`);
      return json(200, { accessToken: current });
    }
    const auth = (init?.headers as Record<string, string> | undefined)?.Authorization;
    return auth === `Bearer ${current}` ? json(200, { ok: true }) : json(401, {});
  };
  return { fetchImpl, calls };
}

describe('AuthSession refresh (ADR-0010)', () => {
  it('concurrent 401s trigger ONE refresh (single flight), then every caller retries and succeeds', async () => {
    const { fetchImpl, calls } = fakeBackend();
    const auth = new AuthSession(fetchImpl, undefined);
    const api = createApi(auth, fetchImpl);
    const results = await Promise.all([api.get('/a'), api.get('/b'), api.get('/c')]);
    expect(results.every((r) => r.ok)).toBe(true);
    expect(calls.filter((c) => c === '/api/auth/refresh')).toHaveLength(1);
  });

  it('refresh runs under the cross-tab Web Lock', async () => {
    const { fetchImpl } = fakeBackend();
    const taken: string[] = [];
    const locks: LockManagerLike = { request: (name, cb) => { taken.push(name); return cb(); } };
    await new AuthSession(fetchImpl, locks).refresh();
    expect(taken).toEqual(['chat-refresh-token']);
  });

  it('a failed refresh leaves the request at 401 and clears the access token', async () => {
    const { fetchImpl } = fakeBackend({ refreshOk: false });
    const auth = new AuthSession(fetchImpl, undefined);
    const r = await createApi(auth, fetchImpl).get('/a');
    expect(r).toEqual({ ok: false, status: 401 });
    expect(auth.accessToken()).toBeNull();
  });

  it('reads the user id from the access token', async () => {
    const { fetchImpl } = fakeBackend();
    const auth = new AuthSession(fetchImpl, undefined);
    await auth.refresh();
    expect(auth.userId()).toBe('user-1-1');
  });
});
