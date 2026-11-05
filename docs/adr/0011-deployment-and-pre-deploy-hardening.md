# ADR-0011 — Deployment and Pre-Deploy Hardening (single instance)

**Status:** Accepted (hardening part)  **Date:** 2026-11-06  **Phase:** 6
The deployment topology, pipeline, rollback and backups are added to this ADR in Phase 6 steps 4 and 5.

## Context
Phase 6 puts the app on the public internet: a browser UI and one VPS that redeploys on every merge to `main`. Until now, nothing limited how often anyone could register, log in or ask for WebSocket tickets, registration was open to anyone, and nothing stopped a production process from running with the dev profile's secrets. This ADR records the controls added **before** the first deploy. It makes no capacity or "production-grade" claims.

## Decision 1: rate limits in the app, in memory, checked before the expensive work
| Limit (default; heuristics) | Key | Where it's checked |
|---|---|---|
| register: 5 per hour | client IP | `AuthController`, before the invite check and BCrypt |
| login: 20 per minute | client IP | `AuthController`, before BCrypt |
| login: 5 per minute | (client IP, username) | `AuthController`, before BCrypt |
| WS ticket: 30 per minute | user | `WsTicketController` (`POST /api/ws/ticket`) |
| failed WS handshakes: 20 per minute | client IP | `WsAuthHandshakeInterceptor`, checked **before** the origin and ticket checks, so a blocked IP can't consume or probe tickets |

- **Response:** `429 RATE_LIMITED` with `Retry-After` (seconds). A blocked handshake gets HTTP 429, which a browser sees only as a failed connect.
- **Why in the app, not the proxy:**
  - the app knows the username and the user, so it can key limits on them;
  - the limits are testable in the integration suite;
  - stock Caddy has no rate limiter, and adding one would need a custom `xcaddy` build.
- **Algorithm:** `FixedWindowRateLimiter`, a fixed window per key. Memory is bounded: when `ratelimit.max-keys` (100,000) keys are tracked, expired windows are evicted first. If every window is still live, a **new** key is refused (fail closed) instead of growing the map. A fixed window can admit up to 2× the limit across a window boundary; that's acceptable for abuse limits.
- **Client IP:** `request.getRemoteAddr()`. With `server.forward-headers-strategy: native`, Tomcat's RemoteIpValve takes it from `X-Forwarded-For` **only when the request comes from a trusted proxy** (private or loopback address). In production, the app has no public port, and Caddy sets `X-Forwarded-For` itself (step 4 checks that a spoofed header from outside doesn't change the IP).
- **The lockout tradeoff for login:**
  - **Protection:** the (IP, username) key stops one source hammering one account. The per-IP key stops one source spraying many accounts.
  - **Cost:** an attacker can't lock a victim out from a *different* IP (the victim's IP has its own counters), but anyone sharing the victim's IP can.
  - **No per-username key:** a global per-username limit would let anyone lock any account out.
- **Shared NAT:** users behind one NAT (an office, a campus, mobile carrier-grade NAT) share the per-IP limits. Five registrations per hour from one office is the visible cost. The invite code makes registration rare anyway.
- **Per instance:** two instances double the effective limits. Phase 4 moves the counters to Redis.

## Decision 2: invite-only registration, checked first
- **Modes:**
  - `registration.mode=invite` is the default: the request needs `inviteCode` equal to `REGISTRATION_INVITE_CODE`, compared in constant time.
  - **Fails closed:** with no code configured, registration is closed, and a blank code never matches.
  - `open` is used only in `dev`, the test profiles and `bench`.
- **Order:** the invite check runs **before** the username and email conflict checks. Without the code, every attempt gets `403 REGISTRATION_CLOSED`, so nobody can learn which usernames or emails exist. With the code, conflicts are reported as before (`409`).
- **Tests:** `RegistrationPolicyTest` (no code, a wrong code or a blank code → 403 and no row; a taken username or email without the code → 403, not 409). `RegistrationPolicyUnitTest` covers the unconfigured, blank and unknown-mode cases.

## Decision 3: seeded demo accounts, public data only
- **What gets seeded:** when `DEMO_PASSWORD` is set (≥ 8 characters), `DemoSeeder` creates `demo1`, `demo2` and `demo3` with that password and one group, "Demo room", owned by `demo1`. It's idempotent, and safe on every start.
- **When `DEMO_PASSWORD` changes:** the next start **replaces the demo users' password hashes**, so the old password stops working for new logins. Sessions that already exist are not revoked; they end when their refresh tokens expire.
- **To reset the demo data** (messages, extra groups), restore an empty database. That's a manual operation, not automated.
- **The demo is public:** anything written in it is readable by anyone with the demo password. Never put personal or real data there. The UI says so.

## Decision 4: a `prod` profile that can't be mixed with dev secrets
- **`application-prod.yml`:** graceful shutdown (in-flight requests finish; sockets close with `1001`), INFO logging, and no secrets.
- **`ProfileGuard`:** refuses to start when `prod` is combined with `dev` or `bench`.
- **The image:** the production image will not contain `application-dev.yml` or `application-bench.yml` at all (step 4).
- **Production configuration checklist** (all from the server's env file, none in the repo):
  - `SPRING_PROFILES_ACTIVE=prod`;
  - `JWT_SECRET` (≥ 32 bytes; startup refuses otherwise);
  - `WS_ALLOWED_ORIGINS=https://<domain>` (exact; startup refuses if it's missing);
  - `REGISTRATION_INVITE_CODE` (registration is closed if it's unset);
  - `METRICS_PASSWORD` (optional: without it, `/actuator/prometheus` isn't exposed);
  - `DEMO_PASSWORD` (optional);
  - `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.

## Consequences
- Abuse limits exist but are per instance, approximate (fixed window) and keyed by IP behind a trusted proxy. They're not a defence against a distributed attacker.
- New error codes: `403 REGISTRATION_CLOSED` and `429 RATE_LIMITED`. A new close code, `4007` ([ADR-0006](0006-websocket-frame-protocol-v1.md) Decision 2a).
- Tests: `FixedWindowRateLimiterTest`, `RateLimitTest`, `RegistrationPolicyTest`, `RegistrationPolicyUnitTest`, `SocketCapTest`, `DemoSeedTest`, `ProfileGuardTest`, each with a mutation shown to fail it.
