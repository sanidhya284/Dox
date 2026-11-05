/**
 * Auth in the browser (ADR-0010):
 * - the access token (15 min) lives ONLY in memory (this object), never in localStorage;
 * - the refresh token is the HttpOnly cookie `chat_rt`, which scripts can't read; the browser sends it to
 *   /api/auth/* only;
 * - refresh is single-flight within a tab, and serialized ACROSS tabs with the Web Locks API. Two tabs
 *   refreshing with the same cookie would look like a replay and revoke the whole family.
 */

export interface LockManagerLike {
  request<T>(name: string, callback: () => Promise<T>): Promise<T>;
}

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export interface AuthResult {
  ok: boolean;
  status: number;
  code?: string;
}

const REFRESH_LOCK = 'chat-refresh-token';

export class AuthSession {
  private access: string | null = null;
  private inflight: Promise<boolean> | null = null;

  constructor(
    private readonly fetchImpl: FetchLike = (i, init) => fetch(i, init),
    private readonly locks: LockManagerLike | undefined =
      typeof navigator !== 'undefined' && 'locks' in navigator
        ? (navigator.locks as unknown as LockManagerLike) : undefined,
  ) {}

  accessToken(): string | null {
    return this.access;
  }

  /** The user id: the access token's `sub` claim (the payload is only decoded, never trusted for auth). */
  userId(): string | null {
    if (!this.access) {
      return null;
    }
    try {
      const payload = this.access.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(atob(payload)).sub ?? null;
    } catch {
      return null;
    }
  }

  async login(username: string, password: string): Promise<AuthResult> {
    return this.authenticate('/api/auth/login', { username, password });
  }

  async register(r: { username: string; email: string; password: string; displayName: string; inviteCode: string }):
    Promise<AuthResult> {
    return this.authenticate('/api/auth/register', r);
  }

  async logout(): Promise<void> {
    this.access = null;
    await this.fetchImpl('/api/auth/logout', { method: 'POST', credentials: 'same-origin' });
  }

  /**
   * One refresh at a time per tab (concurrent callers share it), and per browser (the Web Lock).
   * Resolves false when the cookie is missing, expired or revoked: log in again.
   */
  refresh(): Promise<boolean> {
    if (!this.inflight) {
      const run = async (): Promise<boolean> => {
        const r = await this.fetchImpl('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' });
        if (!r.ok) {
          this.access = null;
          return false;
        }
        this.access = (await r.json()).accessToken;
        return true;
      };
      const locked = this.locks ? this.locks.request(REFRESH_LOCK, run) : run();
      this.inflight = locked.finally(() => {
        this.inflight = null;
      });
    }
    return this.inflight;
  }

  private async authenticate(path: string, body: unknown): Promise<AuthResult> {
    const r = await this.fetchImpl(path, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const json = await r.json().catch(() => ({}));
    if (!r.ok) {
      return { ok: false, status: r.status, code: json.code };
    }
    this.access = json.accessToken;
    return { ok: true, status: r.status };
  }
}
