import type { Api, ApiResult } from '../sync/client';
import type { AuthSession, FetchLike } from './auth';

/**
 * Authenticated JSON REST for the sync client and the UI. On a 401 it refreshes ONCE (shared by every
 * concurrent caller, see AuthSession.refresh) and retries; if the refresh fails, the result stays 401
 * and the app shows the login screen.
 */
export function createApi(auth: AuthSession, fetchImpl: FetchLike = (i, init) => fetch(i, init)): Api {
  async function request<T>(method: string, path: string, body?: unknown): Promise<ApiResult<T>> {
    const send = (token: string | null) => fetchImpl(path, {
      method,
      credentials: 'same-origin',
      headers: {
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    try {
      const used = auth.accessToken();
      let r = await send(used);
      if (r.status === 401) {
        // Someone else may already have refreshed; otherwise refresh once (single flight) and retry.
        if (auth.accessToken() !== used || await auth.refresh()) {
          r = await send(auth.accessToken());
        }
      }
      if (!r.ok) {
        return { ok: false, status: r.status };
      }
      const text = await r.text();
      return { ok: true, status: r.status, data: (text ? JSON.parse(text) : undefined) as T };
    } catch {
      return { ok: false, status: 0 }; // network error: callers retry later
    }
  }
  return {
    get: <T>(path: string) => request<T>('GET', path),
    post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  };
}
