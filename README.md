# Chat Platform

A real-time chat backend built as a learning project for backend engineering: a small, correct, well-tested system whose design decisions are written down and defended in [ADRs](docs/adr/). PostgreSQL is the source of truth; WebSockets are only a delivery optimization.

## Status

| Phase | State |
|---|---|
| 0: skeleton, Flyway, CI | Done |
| 1: REST core (auth, conversations, ordered idempotent messages, keyset paging) | Done |
| 2a/2b-i: WebSocket one-time tickets, authenticated handshake | Done |
| 2b-ii: connection registry, socket lifetime, closing sockets on token theft | Done |
| 2c: WebSocket send, ack, push ([ADR-0006](docs/adr/0006-websocket-frame-protocol-v1.md)) | Done (PR open) |
| 2d-lite: heartbeats and idle close, delivered/read cursors ([ADR-0007](docs/adr/0007-heartbeats-and-read-cursors.md)) | Done (PR open) |
| 2e: React UI (presence and typing deferred) | Minimal UI done in Phase 6 (`frontend/`) |
| 3: reliable delivery and recovery (catch-up APIs, reference client, convergence under faults) | Done (PR open) |
| 5-lite: metrics, load harness, one measured bottleneck ([ADR-0009](docs/adr/0009-observability-and-measurement.md)) | Done (PR open): metrics, load harness, measured on one laptop (below) |
| 6: browser UI + auto-deploy (single instance; [ADR-0011](docs/adr/0011-deployment-and-pre-deploy-hardening.md)) | In progress: steps 1 (pre-deploy hardening), 2 (refresh token in an HttpOnly cookie) 3 (browser UI), 4 (compose stack + Caddy, smoke and e2e) and 5 (server prepared, deploy pipeline built) done; first deploy pending |
| 4, 7 onward: multi-instance, outbox, ... | Not started |

Details and the next steps: [docs/HANDOFF.md](docs/HANDOFF.md).

## Stack
Java 21, Spring Boot 3.3, Gradle 8.10, PostgreSQL 16, Flyway, Spring JDBC (`JdbcClient`, explicit SQL), HikariCP, Spring Security with JWT (HS256) and BCrypt, Testcontainers, JUnit 5, GitHub Actions. UI: Vite + React 18 + TypeScript in `frontend/`, tested with Vitest. `load-tests/` is a placeholder (the load harness lives in `backend/src/test/java/.../load`).

## Run locally
Prerequisites: Java 21 and Docker.

```bash
cd deploy && docker compose up -d          # PostgreSQL on localhost:5432
cd ../backend && SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

The backend listens on http://localhost:8080. `curl http://localhost:8080/actuator/health` returns `{"status":"UP"}` (no component details, by design).

The `dev` profile supplies a development-only JWT secret and the local frontend origins. Without it the app **refuses to start** unless you set:
- `JWT_SECRET`: at least 32 bytes.
- `WS_ALLOWED_ORIGINS`: comma-separated exact origins allowed to open WebSockets, e.g. `https://app.example.com`. A blank entry or `*` is refused.

**Registration is invite-only by default:** `POST /api/auth/register` needs `"inviteCode"` equal to `REGISTRATION_INVITE_CODE`. With no code configured, registration is closed (`403 REGISTRATION_CLOSED`). The `dev` profile keeps registration open. `DEMO_PASSWORD` (optional, at least 8 characters) seeds `demo1`, `demo2`, `demo3` and a "Demo room"; **demo data is public**, so never put real data there.

**Rate limits** (in memory, per instance; `429 RATE_LIMITED` with `Retry-After`):
- register: 5 per hour per IP;
- login: 20 per minute per IP, and 5 per minute per (IP, username);
- WebSocket tickets: 30 per minute per user;
- failed WebSocket handshakes: 20 per minute per IP.

They're checked before BCrypt and before any ticket is touched. Users behind one NAT share the per-IP limits. Details: [ADR-0011](docs/adr/0011-deployment-and-pre-deploy-hardening.md).

**Production** uses `SPRING_PROFILES_ACTIVE=prod` (never together with `dev` or `bench`; startup refuses). The configuration checklist is in [ADR-0011 Decision 4](docs/adr/0011-deployment-and-pre-deploy-hardening.md#decision-4-a-prod-profile-that-cant-be-mixed-with-dev-secrets).

Optional: `WS_MAX_SOCKETS_PER_USER` (default `10`) caps open sockets per user; the next one is closed with `4007`. `WS_SESSION_MAX_LIFETIME` (ISO-8601 duration, default `PT15M`) is how long a WebSocket may stay open; zero or negative values refuse startup. `WS_MAX_TEXT_FRAME_BYTES` (default `32768`) is the largest text frame accepted. Heartbeats: `WS_HEARTBEAT_INTERVAL` (default `PT25S`) and `WS_HEARTBEAT_TIMEOUT` (default `PT60S`, must be at least twice the interval). Push tuning: `WS_SEND_TIME_LIMIT_MS` (default `5000`) and `WS_SEND_BUFFER_BYTES` (default `524288`) bound how long and how much a single socket may lag before it is closed with `4003`; `WS_PUSH_THREADS` (default `2`) and `WS_PUSH_QUEUE_CAPACITY` (default `10000`) size the push executor.

Metrics: `/actuator/prometheus` needs HTTP Basic as user `metrics` with `METRICS_PASSWORD` (at least 16 characters; without it scraping is disabled). The `dev` profile sets a dev-only password. Basic sends the password on every request, so use TLS in production. `/actuator/health` stays public. Application meters are named `chat.*` (sends, acks, sockets, closes, tickets, cursors, heads, dropped pushes); see [architecture.md, Metrics](docs/architecture.md#metrics-adr-0009).

All configuration: [architecture.md, Configuration](docs/architecture.md#configuration).

### The whole stack in Docker (as deployed)
Postgres, the app (`prod` profile) and Caddy (TLS + UI + proxy) on https://localhost (Caddy's local CA, so your browser warns once):

```bash
cd deploy
cp .env.local.example .env                                   # local-only values; .env is git-ignored
docker compose -f compose.yml -f compose.build.yml up -d --build
cd .. && deploy/smoke.sh                                     # exposure, headers, logs, fail-closed image, XFF
cd frontend && npx playwright install chromium && npm run e2e   # demo1 sends, demo2 sees it live
```

Log in as `demo1` / `local-demo-password`. Stop the stack with `docker compose -f compose.yml down` (add `-v` to wipe its database). It needs ports 80 and 443 free. How it's put together: [ADR-0011](docs/adr/0011-deployment-and-pre-deploy-hardening.md).

### How deploys work (single instance; not production-grade)
**Live demo: https://dox-chat.in** (temporary; one small server, demo data is public). It runs on one AWS EC2 `t3.small` in Sydney; `www.dox-chat.in` and the old `dox.work.gd` redirect there. Details: [ADR-0011](docs/adr/0011-deployment-and-pre-deploy-hardening.md); step by step: [docs/AWS-DEPLOY-RUNBOOK.md](docs/AWS-DEPLOY-RUNBOOK.md).
- **CI** (every PR and push touching code): backend unit + integration, the prod-jar check, frontend lint, typecheck, Vitest and build, then the whole stack in compose with Playwright and `smoke.sh`.
- **Deploy** (**automatic**: every push to `main` whose CI passes; docs-only pushes don't deploy):
  1. a gate checks that CI is green for that exact commit;
  2. images are pushed to GHCR (`sha-<short>`);
  3. the job SSHes in with a pinned host key and runs `deploy.sh`. One deploy runs at a time, and none is ever cancelled.
- **Migrations are additive only** (expand/contract), enforced in CI, so a rollback still works against a newer schema.
- **`deploy.sh`:** pulls, restarts, then checks health, a demo login, a ticket, a socket, and a message send, ack and read-back through Caddy. It rolls back to the previous tag if any of that fails.
- **During a deploy,** every WebSocket closes once and clients reconnect and catch up.
- **Backups:** daily `pg_dump` on the server (keeps 7), plus a restore drill script. Copy backups off the server yourself.
- **Not covered:** high availability, zero-downtime deploys, and any capacity claim.

### The browser UI (development)
Prerequisites: Node 22 (npm 10). With the backend running as above (`dev` profile on :8080):

```bash
cd frontend && npm ci && npm run dev        # http://localhost:5173
```

Vite proxies `/api` and `/ws` to `localhost:8080`, so the UI and the API share one origin, as they do behind Caddy in production: no CORS. The `dev` profile keeps registration open and lets the refresh cookie work over plain `http://localhost`.

Frontend checks: `npm run lint` (bans `dangerouslySetInnerHTML`), `npm run typecheck`, `npm test` (Vitest).

The UI covers:
- register and login;
- the conversation list with unread counts;
- the message pane and composer;
- read cursors;
- live push with reconnect.

It doesn't create conversations yet (use the seeded "Demo room", or create groups through the API), and it shows senders as "You" or a short user id.

## Tests
```bash
cd backend
./gradlew test               # unit tests (H2), no Docker needed
./gradlew integrationTest    # integration tests against real PostgreSQL via Testcontainers; needs Docker running
```

Results are in `backend/build/test-results/{test,integrationTest}/*.xml`.

**Docker Engine 29:** it rejects the API version Testcontainers 1.20.1 asks for, so containers fail to start with HTTP 400. Fix it once per machine (CI does the same):
```bash
echo "api.version=1.44" > ~/.docker-java.properties
```

**Load harness** (on demand, never in CI): `./gradlew loadTest` against a separately started app (`bench` profile) and a dedicated benchmark database (`deploy/bench/bench-db.sh`). Method, scenarios, every run and the analysis: [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

## API and WebSocket
The REST endpoints are `/api/auth/*` (register, login, refresh, logout; the refresh token is set and read **only as an HttpOnly, Secure, SameSite=Strict cookie `chat_rt` scoped to `/api/auth`**, never in a body; refresh and logout need an allowed `Origin`: [ADR-0010](docs/adr/0010-refresh-token-cookie.md)), `/api/conversations/*` (direct and group conversations, sending and paging messages; a message body is at most 4,000 characters, longer bodies get `400 VALIDATION_ERROR`), `GET /api/conversations/{id}/messages?after={seq}&limit={n}` (catch up: messages after a seq, oldest first, exact `hasMore`; `?before=` pages backwards), `GET /api/conversations/heads` (`[{conversationId, lastSeq}]` for your conversations: a cheap "am I behind?" check), `POST /api/conversations/{id}/cursor` (`{"kind":"read"|"delivered","seq":42}`: moves your cursor forward, never back; read implies delivered), `POST /api/ws/ticket` and `/actuator/health`. A conversation's `unreadCount` counts messages after your read cursor that *others* sent; `GET /api/conversations/{id}` also returns every participant's `lastReadSeq` / `lastDeliveredSeq`, and the list returns `myLastReadSeq`. Both also return `lastSeq`, the conversation's newest seq. Full table: [architecture.md, API Surface](docs/architecture.md#api-surface).

Connecting a WebSocket:
1. `POST /api/ws/ticket` with `Authorization: Bearer <access token>` returns `{"ticket": "...", "expiresInSeconds": 30}`.
2. Within 30 seconds, open `ws://localhost:8080/ws?ticket=<ticket>` from an allowed `Origin`. A ticket works once.
3. A rejected handshake (bad, used or expired ticket, or an origin not on the list) gets HTTP `401`. A rejection for the origin leaves the ticket usable.

The server pings every socket every `WS_HEARTBEAT_INTERVAL` with a WebSocket ping (browsers answer with a pong automatically; other clients must answer pings) and closes sockets that stay silent past `WS_HEARTBEAT_TIMEOUT`. The server tracks each open socket per user. Frames follow protocol v1 ([ADR-0006](docs/adr/0006-websocket-frame-protocol-v1.md)): JSON text frames `{"v":1,"type":"..."}`. A client sends `{"v":1,"type":"send","conversationId":"...","clientMsgId":"...","body":"..."}`; invalid content gets an `error` frame (`{"v":1,"type":"error","clientMsgId":...,"code":"VALIDATION_ERROR","message":"..."}`) and the socket stays open. A valid send is stored exactly like a REST send, and only after it has committed does the sender get `{"v":1,"type":"ack","clientMsgId":"...","messageId":"...","conversationId":"...","seq":42,"createdAt":"..."}`; resending the same `clientMsgId` returns the same ack without storing a second message.

Every new message (sent over WebSocket or REST) is then pushed, after commit, to every other open socket of the conversation's current participants, including the sender's other tabs: `{"v":1,"type":"message","message":{"id":"...","conversationId":"...","seq":42,"senderId":"...","clientMsgId":"...","body":"...","createdAt":"..."}}`. Pushes are an optimisation, not a guarantee: they may arrive out of `seq` order, may be dropped under load, and a socket that cannot keep up is closed with `4003`. Cursors over WebSocket: send `{"v":1,"type":"cursor","conversationId":"...","kind":"read","seq":42}` (no reply on success; errors come as an `error` frame with `clientMsgId: null`). When someone's cursor actually moves forward, every other socket of the participants gets `{"v":1,"type":"cursor","conversationId":"...","userId":"...","lastDeliveredSeq":42,"lastReadSeq":42}`. Clients should debounce cursor frames (send only the highest seq, at most about once per second). **The history endpoint is the source of truth**: order by `seq`, and after a reconnect page `GET /api/conversations/{id}/messages` for anything missed.

Close codes sent by the server:

| Code | Meaning | Client should |
|---|---|---|
| `4001` | Socket lifetime reached (`WS_SESSION_MAX_LIFETIME`, default 15 minutes after the handshake) | Get a fresh ticket and reconnect |
| `4002` | Credentials revoked: a refresh token of this user was replayed (theft signal), so all their sockets are closed | Log in again |
| `4003` | Slow consumer: the socket fell behind the send-time or buffer limit | Reconnect and recover missed messages from history |
| `4006` | Idle timeout: no pong or frame within `WS_HEARTBEAT_TIMEOUT` | Reconnect; recover from history |
| `4007` | Too many sockets: the user already has `WS_MAX_SOCKETS_PER_USER` open | Don't reconnect automatically; close a tab or device |
| `4004` | Unsupported protocol version (`v` is not 1) | Fix the client |
| `4005` | Unknown frame `type` | Fix the client |
| `1007` | Frame is not valid protocol JSON (envelope, or a `clientMsgId` that is not a UUID) | Fix the client |
| `1009` | Frame larger than `WS_MAX_TEXT_FRAME_BYTES` | Fix the client |
| `1003` | Binary frame (not part of the protocol) | Fix the client |
| `1008` | No authenticated user on the socket (should not happen) | Treat as a bug; get a new ticket |

## Writing a client
Pushes are an optimisation; PostgreSQL is the truth. A correct client (full algorithm and reasoning: [ADR-0008](docs/adr/0008-client-sync-protocol.md); a working reference implementation is in `backend/src/test/java/com/chatplatform/client/ReferenceClient.java`):
1. Give each send a `clientMsgId` once and keep it for every retry; resend if no ack arrives within a timeout and after every reconnect. Treat the ack as your own copy of the message.
2. Reconnect with a fresh ticket and exponential backoff with full jitter. On `4002` log in again; on `4007` stop (too many open sockets); on `1007`/`4004`/`4005` stop (it is a client bug); otherwise reconnect.
3. **Subscribe first, then fetch:** open the socket, then page `GET .../messages?after=<your contiguous seq>` where `GET /api/conversations/heads` says you are behind.
4. Deduplicate by message id, order by `seq`; if a push skips ahead of your contiguous seq, fetch the gap.
5. Every ~30 s (jittered) compare `heads` with your contiguous seq and fetch if behind: this is the only way to notice that the *last* push was lost.
6. On a 401, refresh the access token once (shared by concurrent requests, and in a browser across tabs) and retry. Keep the access token in memory; the refresh token is the `chat_rt` cookie.

**Which client is tested how:** the browser UI's sync code (`frontend/src/sync/client.ts`) is a TypeScript port of the Java reference client. It's unit-tested with a fake server, a fake socket and fake timers (dedupe, subscribe-first, gap fetch, anti-entropy, same-id resend, close codes, single-flight refresh; each with a negative form). **The convergence-under-faults guarantee below is tested on the Java reference client only, not on the TypeScript client.**

`ConvergenceTest` checks this end to end: 8 clients under seeded faults (dropped connections, pushes and acks, duplicated and delayed sends, failed commits) all end with exactly the database's history.

## What is guaranteed (each covered by tests)
Per-conversation ordering without gaps, idempotent sends (`client_msg_id`), a 4,000-character body limit, atomic refresh-token rotation with theft detection, `404` for non-members, one-time WebSocket tickets, exact origin checks, per-user socket tracking, acks only after commit, pushes only after commit and exactly once per message per socket, socket lifetime, and closing a user's sockets after a refresh-token replay (only once the revocation has committed). The precise list, with test names: [architecture.md, Behaviour Guarantees](docs/architecture.md#behaviour-guarantees-each-covered-by-an-integration-or-unit-test).

Under seeded faults, reference clients converge to exactly the stored history (one instance, bounded workload; [ADR-0008](docs/adr/0008-client-sync-protocol.md)). Tests that run on a laptop are correctness checks, not benchmarks. The load measurements are below.

## Measured (Phase 5-lite)
**Results are for this machine and this workload; they are not capacity claims.**

**Setup:**
- One laptop: Intel i7-1068NG7 with 8 logical CPUs, macOS.
- Postgres 16 runs in Docker Desktop's VM.
- The load generator runs on the same machine.
- Each point is 3 runs of a 60 s window, measured open-loop from the intended send time.
- Outcome labels come from the current classifier, applied to the recorded fields; 2 of 68 changed after the run, and the run-time label is kept in each file.

Every run, including the relabelled and aborted ones: [BENCHMARKS.md section 6](docs/BENCHMARKS.md#6-results-step-4-measured-2026-11-05).

**Hot conversation** (10 senders, one group): **held through 200/s, unstable at 300/s.**
- **200 msg/s, pool 10:** all 3 runs kept up. Ack p50 was 3.7–3.8 ms and p99 11.2–31.3 ms ([1](docs/benchmarks/20261105-195410-A-r200-A-pool10-p1.json), [2](docs/benchmarks/20261105-200445-A-r200-A-pool10-p2.json), [3](docs/benchmarks/20261105-201519-A-r200-A-pool10-p3.json)).
- **300 msg/s, pool 10:** 1 of 3 runs was SATURATED. The server stored 269.1 msg/s and ack p50 reached 3,537 ms ([run](docs/benchmarks/20261105-201712-A-r300-A-pool10-p3.json)).
- **300 msg/s, pool 30:** 3 of 3 runs were SATURATED, at 254.5–285.7 msg/s stored. Pool 30 didn't help in these runs, and its p99 was higher; the cause wasn't investigated ([1](docs/benchmarks/20261105-210215-A-r300-A-pool30-p1.json), [2](docs/benchmarks/20261105-211303-A-r300-A-pool30-p2.json), [3](docs/benchmarks/20261105-212343-A-r300-A-pool30-p3.json)).

**The same load over 10 groups, 300 msg/s:** all 3 runs kept up, at 299.6 msg/s stored, with ack p99 28.5–32.1 ms ([1](docs/benchmarks/20261105-203006-B-r300-B-pool10-p1.json), [2](docs/benchmarks/20261105-204043-B-r300-B-pool10-p2.json), [3](docs/benchmarks/20261105-205119-B-r300-B-pool10-p3.json)).

**Fan-out to 200 sockets, 20 msg/s** (4,000 pushes/s): send→receive p99 was 20.9–22.8 ms, with 0 dropped pushes and 0 `4003` closes ([1](docs/benchmarks/20261105-213109-C-r20-C-pool10-p1.json), [2](docs/benchmarks/20261105-213822-C-r20-C-pool10-p2.json), [3](docs/benchmarks/20261105-214723-C-r20-C-pool10-p3.json)).

**Client retries past the limit:** with the client's default 5 s ack timeout at 300 msg/s, there were 17,733 resends, and 2,980 server-side duplicates competed for the same row ([run](docs/benchmarks/20261105-201916-A-r300-A-ack5s-p1.json)).

**Bottleneck:** the per-conversation row lock (`SELECT ... FOR UPDATE` until commit). In the hot conversation at 300 msg/s, 90–93% of active database samples were row-lock waits, and the 10-group control kept up at the same rate. How much of each lock hold is the commit flush is a hypothesis that wasn't tested. A's p50 falling as the rate rose is unexplained. Evidence, caveats and what would change this: [BENCHMARKS.md section 7](docs/BENCHMARKS.md#7-bottleneck-analysis).

## Known limitations
The main ones:
- Logout does not close open WebSockets.
- The connection registry and pushes are single-instance (Phase 4: Redis fan-out).
- Pushes may arrive out of `seq` order and may be dropped when the push queue is full; clients recover from history.
- No per-socket send rate limit yet. The auth and ticket rate limits are in memory and per instance.
- `GET` requests and message sends aren't rate-limited.
- The 401 from the security filter has an empty body.
- If a refresh response is lost after the server rotated the token, the next refresh looks like a replay: the user is logged out everywhere ([ADR-0010](docs/adr/0010-refresh-token-cookie.md)).
- Read receipts are visible to every participant (no privacy toggle); "delivered" is reported by the client.

Full list: [architecture.md, Known Limitations](docs/architecture.md#known-limitations).

## Design decisions
- [ADR-0001: Modular monolith](docs/adr/0001-modular-monolith.md)
- [ADR-0002: Message sequencing and idempotency](docs/adr/0002-message-sequencing-and-idempotency.md)
- [ADR-0003: Authentication (JWT + rotating refresh tokens)](docs/adr/0003-authentication-strategy.md)
- [ADR-0004: WebSocket tickets in PostgreSQL](docs/adr/0004-websocket-authentication-tickets.md)
- [ADR-0005: Connection registry and after-commit socket closing](docs/adr/0005-connection-registry-and-after-commit-socket-closing.md)
- [ADR-0006: WebSocket frame protocol v1](docs/adr/0006-websocket-frame-protocol-v1.md)
- [ADR-0007: Heartbeats and read cursors](docs/adr/0007-heartbeats-and-read-cursors.md)
- [ADR-0008: Client sync protocol](docs/adr/0008-client-sync-protocol.md)
- [ADR-0009: Observability and honest measurement](docs/adr/0009-observability-and-measurement.md)
- [ADR-0010: Refresh token in an HttpOnly cookie](docs/adr/0010-refresh-token-cookie.md)
- [ADR-0011: Deployment and pre-deploy hardening](docs/adr/0011-deployment-and-pre-deploy-hardening.md)
- [Architecture overview](docs/architecture.md)
- [Interview notes](docs/INTERVIEW_NOTES.md): per milestone, the problem, the bug we actually found, the test that proves the fix, the tradeoff, and likely follow-up questions
