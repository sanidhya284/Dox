# ADR-0010 — Refresh Token in an HttpOnly Cookie, Access Token in Memory

**Status:** Accepted  **Date:** 2026-11-06  **Phase:** 6
**Amends:** [ADR-0003](0003-authentication-strategy.md), where the refresh token is returned in JSON bodies. Rotation, family revocation and their atomicity are unchanged.

## Context
Phase 6 adds a browser UI. Until now the refresh token (7 days) was returned in JSON and sent back in a JSON body. That suits a test client, but in a browser it means JavaScript holds a long-lived credential: any XSS could copy it and use it from elsewhere for a week.

## Decision
- **The refresh token travels only in a cookie** named `chat_rt`:
  - `HttpOnly`: scripts can't read it;
  - `Secure`: HTTPS only (`auth.refresh-cookie.secure`, true by default and in prod, false only in the `dev` profile for `http://localhost`);
  - `SameSite=Strict`: never sent on cross-site requests;
  - `Path=/api/auth`: sent only to register, login, refresh and logout, never to the rest of the API;
  - `Max-Age` = the token lifetime (7 days).
- **Bodies:** register returns `{user, accessToken}`; login and refresh return `{accessToken}`; no response body contains the refresh token. Refresh and logout take no body.
- **The access token (15 minutes) lives in the client's memory only,** never in `localStorage`. After a reload, the UI calls `/api/auth/refresh` to get a new one.
- **Same origin:** the UI, `/api` and `/ws` are served from one origin behind Caddy (ADR-0011), so there's no CORS configuration at all.
- **CSRF controls** (the cookie is sent automatically, so refresh and logout need them):
  1. `SameSite=Strict`;
  2. **an exact `Origin` check** on refresh and logout (`AuthOriginGuard`). The `Origin` header must equal one of `WS_ALLOWED_ORIGINS`, the list that already guards WebSocket handshakes. A missing or foreign `Origin` gives `403 ORIGIN_NOT_ALLOWED`, and nothing is rotated or revoked.
- **Failure handling:**
  - a failed refresh (invalid, expired or replayed) also clears the cookie (`Max-Age=0`);
  - logout revokes the token if one is present and always clears the cookie.
- **Multi-tab:** all tabs share one cookie. If two tabs refresh at once, the second sends a token the first already rotated: that looks exactly like theft, and revokes the family. The UI therefore serializes refresh **across tabs** with the Web Locks API (`navigator.locks`), as well as within a tab (single flight, ADR-0008).

- **Host-only cookie:** the cookie has **no `Domain` attribute**, so it's sent only to the exact host that set it (asserted in `RefreshCookieTest`).
- **Shared-domain caveat (amended 2026-11-08):** the demo runs on `dox.work.gd`, a shared domain that isn't on the Public Suffix List. Browsers therefore treat every `*.work.gd` site as **same-site** with ours, and `SameSite=Strict` doesn't stop a request coming from another `*.work.gd` site.
  - **What still holds:** the exact `Origin` check (refresh, logout, WebSocket handshake) and the host-only cookie.
  - **The fix:** a domain of our own (or one on the Public Suffix List) restores SameSite as a real boundary.

## Alternatives
| | HttpOnly cookie (chosen) | Both tokens in memory only |
|---|---|---|
| XSS while the page is open | Can act as the user (it can call the API) | Same |
| XSS stealing a credential for later use | Can steal only the 15-minute access token | Can steal the 7-day refresh token |
| Page reload or new tab | Stays logged in (refresh via the cookie) | Logged out every time |
| CSRF | A new surface, closed by SameSite=Strict, the Origin check and same-origin deployment | None |
| Multi-tab | Needs a cross-tab lock (above) | Each tab logs in separately |

**Tradeoff:** the cookie adds a CSRF surface and the multi-tab problem, in exchange for keeping the long-lived credential out of JavaScript and surviving reloads. XSS is still fatal while the page is open, so the UI renders message bodies as text only, and Caddy sets a strict CSP (ADR-0011).

## Consequences
- **Non-browser clients** (the Java reference client, the load harness) read `chat_rt` from `Set-Cookie`, then send it back with an allowed `Origin`.
- **Lost refresh response:** if the server rotates the token but the response, including its `Set-Cookie`, is lost (a network drop or a closed tab), the browser still holds the old token. Its next refresh is indistinguishable from a replay, so the family is revoked, the user's sockets close with `4002`, and the user must log in again. This is the same strictness as before ([architecture.md, Known Limitations](../architecture.md#known-limitations)). A grace window for the just-rotated token would fix it, at the cost of weaker theft detection; it's not implemented.
- **Login CSRF** (logging a victim into the attacker's account) isn't specifically defended. Register and login don't check `Origin`; the impact is low for this app.
- **Tests:**
  - `RefreshCookieTest`: cookie attributes, no token in bodies, rotation sets a new cookie, a missing or foreign Origin changes nothing, logout clears the cookie, no cookie → 401.
  - Re-expressed through the cookie, unchanged in substance: `TokenTheftTest` (replay revokes the family and clears the cookie), `RefreshReplayDeadlockTest`, concurrent refresh with exactly one winner (`Phase1ReviewTest`), `WsTheftCloseTest`.
  - Mutations: no HttpOnly, Secure defaulting to false, `Path=/`, no Origin check, `noRollbackFor` removed.
