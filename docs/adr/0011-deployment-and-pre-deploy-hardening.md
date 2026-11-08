# ADR-0011 — Deployment and Pre-Deploy Hardening (single instance)

**Status:** Accepted  **Date:** 2026-11-06 (amended 2026-11-08: deployment target, ACME staging, pipeline, backups, own domain)  **Phase:** 6

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

## Decision 5: one VPS, Docker Compose, Caddy in front (images, compose, proxy)
```
Internet ──443/80──▶ web (Caddy: TLS, UI files, proxy) ──▶ app:8080 (Spring Boot, prod profile) ──▶ db:5432 (Postgres 16)
```
- **Only Caddy publishes ports** (80, 443). The app and Postgres have none; they're reachable only on the compose network.
- **Images** (built in CI and pushed to GHCR in step 5; locally `compose.build.yml` builds them):
  - **app** (`backend/Dockerfile`):
    - built with `-PprodJar`, so `application-dev.yml` and `application-bench.yml` are **not in the jar**; `verifyProdJar` fails the build otherwise, and `smoke.sh` checks the image;
    - runs as a non-root user on `eclipse-temurin:21-jre`;
    - with no profile and no configuration it refuses to start: the first fail-closed check to fire is `ws.allowed-origins is not set`, and with only `JWT_SECRET` missing it's `jwt.secret is not set` (both checked by `smoke.sh`);
    - the builder stage uses the pinned `gradle:8.10.2-jdk21` image, because the wrapper's download timed out inside a Docker build once.
  - **web** (`deploy/web.Dockerfile`): `caddy:2.8.4-alpine` with the built UI (`/srv`) and `deploy/caddy/Caddyfile`.
- **Caddy routes:**
  - `/actuator/health` → app;
  - every other `/actuator*` → **404** (Prometheus and env stay internal);
  - `/api/*` and `/ws` → app (WebSocket upgrade included);
  - everything else → the UI (`try_files` to `index.html`).
- **Caddy headers:**
  - HSTS `max-age=31536000` with **no preload** (preload is hard to undo; it's off for `localhost`);
  - `X-Content-Type-Options: nosniff`;
  - `Referrer-Policy: no-referrer`;
  - a strict CSP: `default-src 'self'`, `connect-src 'self' wss://<domain>`, `frame-ancestors 'none'`, no inline scripts.
- **Logs:** the access log goes to stdout as JSON, with the `ticket` query parameter deleted and the `Cookie`, `Authorization` and `Set-Cookie` headers dropped.
- **Client IP:** Caddy trusts no upstream proxy, so it **replaces** any client-sent `X-Forwarded-For`. The app trusts that header only from a private-address proxy (Decision 1).
  - `smoke.sh` sends 21 logins with 21 different spoofed `X-Forwarded-For` values: the 21st gets `429`, so the limit counted the real client.
  - **Shown capable of failing:** with Caddy told to trust all proxies, the same check fails (`401` instead of `429`).
- **Postgres:** `postgres:16.4-alpine` with a named volume. Flyway migrates at app startup, so a deploy that brings a new migration applies it before the app reports healthy.
- **The app's healthcheck:** `bash` with `/dev/tcp` (the JRE image has no curl). `/bin/sh` is dash there, which has no `/dev/tcp`, and that bug was found when the first `compose up` reported the app unhealthy.
- **Phase 4 is an addition, not a rewrite:** no service sets `container_name`, and Caddy's upstream is `{$APP_UPSTREAMS:app:8080}`. A later `compose.phase4.yml` override adds `redis` and `app2` and sets `APP_UPSTREAMS="app:8080 app2:8080"` (plus `lb_policy`).

## Decision 6: memory limits and the recommended VPS size (derived from limits, not measured)
- **Limits:**
  - the app container has `mem_limit: 768m`, and the image sets `-XX:MaxRAMPercentage=75` (heap up to about 576 MB) plus `ExitOnOutOfMemoryError`, so Docker restarts it instead of letting it limp;
  - Caddy has `128m`;
  - Postgres has no limit, and the defaults use a few hundred MB at this size.
- **Recommended for Phase 6: 2 vCPU and 2 GB RAM** (plus 1–2 GB swap). App (768 MB) + Postgres (about 300 MB) + Caddy (128 MB) + OS and Docker (about 300 MB) leave a little headroom. At 1 GB, the JVM and Postgres would compete and the OOM killer would decide.
- **Phase 4** adds a second app instance (768 MB) and Redis (about 100 MB): plan for **4 GB**.
- These figures come from the configured limits, not from measurements on a VPS. They are not capacity claims.

## Decision 7: sockets during a deploy
- **What happens:** with one app instance, a deploy restarts it, so every WebSocket closes. Graceful shutdown (`server.shutdown: graceful`, 30 s `stop_grace_period`) lets in-flight requests finish, and the sockets close with `1001`.
- **How clients recover:** they reconnect with full-jitter backoff, then subscribe-then-fetch, and resend unacked outbox entries with the same `clientMsgId`. No message is lost or duplicated; the user sees "Reconnecting…" for the restart time (the app needed about 20 s to start in the local stack).
- **No zero-downtime claim:** two instances in Phase 4 make rolling deploys possible.

## Decision 8: the deployment target (amended 2026-11-08)
- **The machine:** AWS EC2 `t3.small` (x86, 2 vCPU, 2 GB) with Ubuntu 24.04 LTS and 20 GB gp3, in **ap-southeast-2 (Sydney)**, with Elastic IP `3.24.141.155`.
  - **Why Sydney:** Mumbai (ap-south-1) was the first choice because it's closer to India, but the AWS Free plan blocked it.
  - **Cost of Sydney:** higher latency from India, and slightly higher prices.
- **Server preparation** (runbook section 4):
  - Docker Engine and the compose plugin from Docker's apt repository;
  - json-file logs capped at 10 MB × 3;
  - 2 GB swap with `vm.swappiness=10`;
  - fail2ban on sshd; password login is off;
  - a `deploy` user with no sudo and a key-only login, in the `docker` group (root-equivalent, accepted for a single-purpose demo server).
- **Secrets:** the app secrets were generated **on the server** with `openssl` into `/opt/chat/.env` (mode 600, owner `deploy`) and never left it.
- **Hostname, first `dox.work.gd`, now `dox-chat.in`.**
  - **What went wrong with `dox.work.gd`:** it's a free DNSExit name on a **shared domain that isn't on the Public Suffix List**, and that caused three problems:
    1. **Let's Encrypt rate limits are shared** with every other `*.work.gd` user. Mitigation: the first start used the **staging CA** (`ACME_MODE=staging`), switched to `default` once DNS and ports were confirmed, and recorded the issuing CA (Let's Encrypt `YE2`).
    2. **Browsers treat every `*.work.gd` site as same-site,** so `SameSite=Strict` doesn't stop requests from other `*.work.gd` sites. The exact Origin checks and the host-only cookie still held (ADR-0010).
    3. **A large ISP resolver failed DNSSEC validation for the whole `work.gd` zone** (SERVFAIL, while it resolved with checking disabled, and Google and Cloudflare resolved it). Visitors on that ISP couldn't reach the site at all. Found on 2026-11-08 from the author's own connection.
  - **Resolved 2026-11-08 by our own domain `dox-chat.in`:** our own rate-limit budget, a real same-site boundary, and it resolves on that ISP's resolver.
  - **The redirects:** `www.dox-chat.in` and the old `dox.work.gd` get certificates and a **308** to `https://dox-chat.in` (path and query kept), so old links keep working until `work.gd` is dropped. The app's only allowed Origin stays the canonical one.
  - **Mechanism:** `REDIRECT_HOSTS` in the Caddyfile; compose defaults it to `www.<DOMAIN>`, and `smoke.sh` checks it.

## Decision 9: the deploy pipeline, rollback and backups (amended 2026-11-08)
- **CI** (`.github/workflows/ci.yml`): `backend` (unit, integration, `verifyProdJar`), `frontend` (lint, typecheck, Vitest, build) and `e2e`.
  - **The e2e job:** it builds both images with the Docker layer cache, starts the compose stack, runs Playwright (at most 1 retry; attempts and flaky passes are written to the job summary), then runs `smoke.sh`.
  - **Path filters:** `backend/**`, `frontend/**`, `deploy/**`, `.github/workflows/**`.
  - **Caching:** Gradle, `node_modules` (keyed on the lockfile) and Docker layers.
- **Deploy** (`.github/workflows/deploy.yml`), **manual (`workflow_dispatch`) only** for now; it turns automatic after CI on `main` only after several clean manual deploys (runbook section 9).
  1. **gate:** the ref is `main`, and all three CI checks passed for this exact commit.
  2. **images:** build both images and push them to GHCR as `sha-<short>` and `main`.
  3. **deploy:** runs in the GitHub environment `production` (a required reviewer). It uses plain `ssh`/`scp` with the **pinned host key** (`DEPLOY_KNOWN_HOSTS`, `StrictHostKeyChecking yes`), copies `compose.yml` and the scripts to `/opt/chat`, and runs `deploy.sh <tag>`.
- **`deploy.sh`:** `compose pull`, then `up -d` (Flyway migrates at startup), then `postdeploy_check.py` through Caddy:
  - health is UP;
  - demo1 logs in;
  - a ticket is issued and the WebSocket opens with the site's Origin;
  - a message is **sent and acked**;
  - the same message is read back over REST.
  - **On any failure:** redeploy the previous good tag, check it again, and fail the job. The check posts into its own "Deploy checks" group (demo1 + demo2), not the Demo room.
  - **Migrations:** rolling back across one works only for additive (expand/contract) migrations.
- **Backups:** the `deploy` user's crontab (installed by the deploy job) runs `backup.sh` daily at 03:15 UTC: `pg_dump -Fc`, keep 7, mode 600. `restore.sh <dump>` is a **drill**: it restores into a throwaway Postgres container, prints row counts and never touches the live database.
  - **Copy them off-box:** backups on the same server don't survive losing it, so copy them off AWS (runbook section 8.3).
- **Teardown and live restore:** in [the runbook](../AWS-DEPLOY-RUNBOOK.md).

## Consequences
- Abuse limits exist but are per instance, approximate (fixed window) and keyed by IP behind a trusted proxy. They're not a defence against a distributed attacker.
- New error codes: `403 REGISTRATION_CLOSED` and `429 RATE_LIMITED`. A new close code, `4007` ([ADR-0006](0006-websocket-frame-protocol-v1.md) Decision 2a).
- Local stack checks: `deploy/smoke.sh` (exposure, headers, redirect, fail-closed image, cookie flags, a WebSocket through Caddy, the ticket absent from logs, XFF) and one Playwright test (`frontend/e2e/smoke.spec.ts`: demo1 sends, demo2 sees it live, rendered as text; at most 1 retry, reported).
- Tests: `FixedWindowRateLimiterTest`, `RateLimitTest`, `RegistrationPolicyTest`, `RegistrationPolicyUnitTest`, `SocketCapTest`, `DemoSeedTest`, `ProfileGuardTest`, each with a mutation shown to fail it.
