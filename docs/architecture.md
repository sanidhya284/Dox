# Architecture — Phase 1: REST Core

> Status: **Phase 1 — REST Core (complete)**
> Last updated: 2026-10-17

---

## System Overview

```
┌─────────────────────────────────────────────┐
│               React Frontend                │
│  (placeholder — not yet implemented)        │
│  Port: 3000 (planned)                       │
└───────────────┬─────────────────────────────┘
                │  HTTP/REST (JSON)
                │  Authorization: Bearer <JWT>
                ▼
┌─────────────────────────────────────────────┐
│          Spring Boot Backend                │
│  Java 21 · Spring Boot 3.3 · Gradle         │
│  Port: 8080                                 │
│                                             │
│  ┌─────────────────────────────────────┐   │
│  │  JwtAuthFilter (OncePerRequestFilter│   │
│  │  validates Bearer token every req)  │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │  REST Controllers                   │   │
│  │  /api/auth  /api/conversations      │   │
│  │  /api/conversations/{id}/messages   │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │  Service Layer                      │   │
│  │  AuthService · ConversationService  │   │
│  │  MessageService · TokenService      │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │  Repository Layer — JdbcClient      │   │
│  │  (Spring JDBC, explicit SQL)        │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │  HikariCP Connection Pool           │   │
│  │  max: 10 connections                │   │
│  └──────────────┬──────────────────────┘   │
└─────────────────┼───────────────────────────┘
                  │  JDBC
                  ▼
┌─────────────────────────────────────────────┐
│             PostgreSQL 16                   │
│  Port: 5432 (Docker Compose locally)        │
│  Migrations: Flyway (V1–V5)                 │
│                                             │
│  Tables: users, refresh_tokens,             │
│    conversations, conversation_participants,│
│    messages                                 │
└─────────────────────────────────────────────┘
```

---

## Package Structure

```
com.chatplatform/
  shared/
    exception/   ApiException, ErrorCode  — domain errors
    web/         GlobalExceptionHandler, ErrorResponse
    security/    JwtService, SecurityConstants
    util/        RequestIdFilter           — requestId in MDC + ThreadLocal
  user/
    repository/  User entity, UserRepository
    service/     UserService
    dto/         UserResponse
  auth/
    controller/  AuthController            — /api/auth/**
    service/     AuthService, TokenService
    repository/  RefreshToken, RefreshTokenRepository
    security/    JwtAuthFilter, SecurityConfig
    dto/         RegisterRequest, LoginRequest, RefreshRequest, AuthResponse, TokenPair
  conversation/
    controller/  ConversationController    — /api/conversations/**
    service/     ConversationService
    repository/  Conversation, ConversationParticipant,
                 ConversationRepository, ParticipantRepository
    dto/         CreateDirectRequest, CreateGroupRequest, ConversationResponse
  message/
    controller/  MessageController         — /api/conversations/{id}/messages
    service/     MessageService
    repository/  Message, MessageRepository
    dto/         SendMessageRequest, MessageResponse, MessagePage
  health/
    DatabaseHealthChecker, DatabaseHealthIndicator
```

**Modular monolith boundary rule:** No repository class is called by another domain's service. Cross-domain access goes through a service method (e.g. `ConversationService.requireParticipant` called by `MessageService`).

---

## Component Responsibilities

### JwtAuthFilter
Runs before every request. Extracts `Authorization: Bearer <token>`, validates the HMAC-SHA256 signature and expiry, and populates `SecurityContextHolder` with the user's UUID. If the token is absent or invalid, the request reaches Spring Security with no principal and is rejected (401) for any protected route.

### SecurityConfig
- CSRF disabled (stateless API — no sessions, no cookies).
- Session management: `STATELESS` — Spring never creates an `HttpSession`.
- Public routes: `/api/auth/**`, `/actuator/health` and `/actuator/health/**`, plus the exact WebSocket handshake path `/ws` (authenticated by a one-time ticket instead of a JWT; see ADR-0004). Paths below it, such as `/ws/anything`, still need a JWT.
- All other routes require a valid JWT. Unauthenticated requests get a bare `401` (HttpStatusEntryPoint).
- BCrypt cost factor 12 (≈ 300 ms per hash on modern hardware).

### AuthService / TokenService
- Registration: BCrypt hash → INSERT user → issue token pair.
- Login: lookup by username (citext — case-insensitive) → BCrypt verify → issue token pair.
- Refresh: SHA-256(rawToken), then one atomic conditional `UPDATE ... WHERE revoked_at IS NULL AND expires_at > now RETURNING *` claims the token. Only the winner issues a new token in the same family; concurrent losers see a revoked token and trigger family revocation.
- Theft detection: if a revoked token is presented, the entire `family_id` is revoked (see ADR-0003). The revocation is committed even though the request returns 401 (`@Transactional(noRollbackFor = ApiException.class)`).

### ConversationService
- DM uniqueness enforced by `direct_key = min(uid1,uid2) + ":" + max(uid1,uid2)` with a DB `UNIQUE` constraint.
- IDOR protection: inaccessible conversations return `404`, not `403`, on every conversation and message endpoint (see ADR-0003, Decision 4).

### MessageService
- Body limit: at most `MessageLimits.MAX_BODY_CHARS` = 4,000 UTF-16 chars (`String.length()`; most emoji count as 2). Checked by `@Size` on the REST request and again at the start of `sendMessage`, before the membership check (it is pure input validation and reveals nothing about the conversation), so any caller, including the Phase 2c WebSocket path, gets the same rule. Over the limit: `400 VALIDATION_ERROR`, nothing persisted.
- Send order inside one transaction: membership check, `SELECT ... FOR UPDATE` on the conversation row, idempotency lookup, then `UPDATE conversations SET last_seq = last_seq + 1 RETURNING last_seq`, then insert. The row lock serialises concurrent senders; because the idempotency check happens before the increment, duplicates never burn a sequence number. `MAX(seq)+1` is unsafe under concurrency (see ADR-0002).
- Idempotency: `client_msg_id` is client-generated. A retry with the same conversation and body returns the original message. Reusing the key in another conversation or with a different body returns `409 IDEMPOTENCY_KEY_CONFLICT`. `UNIQUE(sender_id, client_msg_id)` stays as the database backstop; if an insert still conflicts, the request returns 409 rather than another message.
- Pagination: keyset (`WHERE seq < :cursor ORDER BY seq DESC LIMIT :n`) — O(LIMIT) cost regardless of conversation size.

### Realtime (WebSocket, `realtime` package)
- **Tickets:** `POST /api/ws/ticket` (JWT required) issues a one-time, 30-second ticket stored as a SHA-256 hash in `ws_tickets` ([ADR-0004](adr/0004-websocket-authentication-tickets.md)).
- **Handshake:** `WsAuthHandshakeInterceptor` checks `Origin` exactly against `ws.allowed-origins`, then consumes the ticket with `DELETE ... RETURNING`, and puts the user id in the session attributes. Any rejection is `401`, and the ticket stays usable.
- **`ChatWebSocketHandler`:** registers each accepted socket in `ConnectionRegistry` under that user id, and unregisters it on close or transport error. A socket without a user id (should not happen) is closed with `1008` and not registered. Every socket is closed with `4001` when its lifetime ends (see WebSocket Socket Lifetime). Incoming frames follow protocol v1 ([ADR-0006](adr/0006-websocket-frame-protocol-v1.md)), parsed by `FrameCodec` with Spring's `ObjectMapper`: protocol violations close the socket (`1007`, `4004`, `4005`); invalid content gets an `error` frame carrying the client's `clientMsgId` and the socket stays open. A valid `send` calls the same `@Transactional` `MessageService.sendMessage` as REST (membership, row lock, idempotency, seq, body limit); the handler is not transactional, so the `ack` is sent after the call returns, i.e. after commit. Any failure, including at commit, becomes an `error` frame (`INTERNAL_ERROR` for unexpected ones, logged with the session id, never the body).
- **Push (Phase 2c):** `MessageService.sendMessage` publishes `MessageCreatedEvent` only for a new row; `MessagePushListener` handles it `@TransactionalEventListener(AFTER_COMMIT)` and queues the push on the bounded `wsPushExecutor` (full queue: drop, log, count). The push asks `ConversationService.activeParticipantIds` (`left_at IS NULL`) for recipients and sends a `message` frame to all their open sockets except the origin socket (which got the ack). REST sends are pushed too.
- **Sending to sockets:** every send (ack, error, push) goes through the registry's per-session `ConcurrentWebSocketSessionDecorator` (send-time and buffer limits). Tomcat's per-session `BLOCKING_SEND_TIMEOUT` is set to the same limit so the writing thread is bounded too. A slow or broken consumer is closed with `4003` through the raw session (once a decorator's limit trips it would rewrite any close status to `4500`) and unregistered.
- **Heartbeats ([ADR-0007](adr/0007-heartbeats-and-read-cursors.md)):** `HeartbeatMonitor` sweeps every `ws.heartbeat.interval` on the realtime scheduler: it pings each tracked socket through its decorator (protocol-level ping; clients answer with a pong automatically) and closes with `4006` any socket with no pong or frame for `ws.heartbeat.timeout`. The handler refreshes a socket's last-seen time on connect, every text frame and every pong. The monitor counts pings sent and pongs received.
- **`ConnectionRegistry`:** in-memory `userId → set of sessions`, so one user can have several tabs or devices. `register` and `unregister` run inside `ConcurrentHashMap.compute`, so adding a user's first socket and removing their last cannot interleave and lose a socket. It exposes session ids only, never raw sessions. It is single-instance; Phase 4 needs cross-instance fan-out. See [ADR-0005](adr/0005-connection-registry-and-after-commit-socket-closing.md).

### Cursors and unread (ADR-0007)
- `conversation_participants.last_delivered_seq` / `last_read_seq` (existing V4 columns). `ConversationService.advanceCursor` checks, in order: membership (same `404` for non-member, left participant, unknown conversation), kind and seq (negative or beyond `last_seq`: `400 VALIDATION_ERROR`), then one guarded UPDATE: read `SET last_read_seq = :seq, last_delivered_seq = GREATEST(last_delivered_seq, :seq) ... AND last_read_seq < :seq`; delivered `SET last_delivered_seq = :seq ... AND last_delivered_seq < :seq`. A stale cursor matches no row and writes nothing; concurrent updates serialise on the row lock and re-check the guard, so a cursor never moves back.
- **Over WebSocket:** a `cursor` frame calls the same `advanceCursor` (with the socket as origin). When the guarded UPDATE changed the row, `ConversationService` publishes `CursorAdvancedEvent`; `MessagePushListener.onCursorAdvanced` (AFTER_COMMIT, bounded push executor) pushes a `cursor` frame to all active participants' sockets except the origin. REST cursor updates are pushed the same way.
- Unread = messages with `seq > last_read_seq` sent by someone else, counted in `ParticipantRepository` (a read-only join on `messages`; one grouped query for the list). `GET /api/conversations/{id}` returns each active participant's cursors; the list returns `myLastReadSeq`.

### Metrics (ADR-0009)
- `ChatMetrics` (`shared.metrics`) owns the application meters; every tag value comes from a fixed enum. `RealtimeMetrics` binds the `chat.ws.sessions` gauge (sockets in `ConnectionRegistry`) and the push executor's `executor.*` meters (`name=wsPushExecutor`). JVM, HikariCP and Tomcat meters come from Spring Boot.
- `chat.messages.sent{channel,outcome}`: channel `ws` when the send came from a socket, else `rest`. `created` and `duplicate` are counted after commit (a rolled-back send is never `created`); `rejected` means `MessageService` refused it (service-side validation, not a member, idempotency conflict). Bean-validation `400`s at the REST controller never reach the service and are not counted.
- `chat.ws.send.ack`: server side only, from the `send` frame being handled to the ack handed to the socket; error replies are not timed. `chat.ws.closes{code}`: counted once per socket in `afterConnectionClosed`; codes 1000, 1001, 1007, 1009, 4001, 4002, 4003, 4005, 4006, anything else `other`. `chat.ws.tickets{outcome}`: issued, consumed by a handshake, rejected (unknown, used or expired). `chat.cursor.updates{outcome}`: advanced or stale, after commit. `chat.heads.requests`. `chat.push.dropped{reason=queue_full}` (the log line and `droppedPushes()` stay).

### HikariCP
- Bounded pool: max 10 connections.
- Application threads (Tomcat default: 200) ≠ DB connections.
- Threads wait up to 30 s for a free connection before failing with a timeout exception.

### Flyway
- Migrations in `src/main/resources/db/migration/`.
- Naming: `V{version}__{description}.sql`.
- **Never edit a migration after it has been applied to any environment.**

| Version | Contents |
|---|---|
| V1 | Enable `pgcrypto` extension |
| V2 | Enable `citext` extension; create `users` table |
| V3 | Create `refresh_tokens` table |
| V4 | Create `conversation_type` enum, `conversations`, `conversation_participants` |
| V5 | Create `messages` table |

---

## Browser UI (Phase 6, `frontend/`)
- **Stack:** Vite + React 18 + TypeScript.
- **Same origin:** served from the same origin as `/api` and `/ws` (the Vite proxy in dev; Caddy in production, ADR-0011), so there's no CORS.
- **`src/api/auth.ts`:**
  - the access token is kept in memory only;
  - after a reload, `POST /api/auth/refresh` with the HttpOnly `chat_rt` cookie gets a new one;
  - refresh is single-flight within a tab and serialized across tabs with the Web Locks API (ADR-0010).
- **`src/api/http.ts`:** authenticated JSON; on a 401 it refreshes once and retries.
- **`src/sync/client.ts`:** the ADR-0008 algorithm, ported from the Java reference client:
  - a stable-`clientMsgId` outbox with ack timeout and resend;
  - full-jitter reconnect;
  - subscribe-then-fetch;
  - dedupe by id and order by `seq`;
  - gap fetch;
  - jittered `heads` anti-entropy;
  - the close-code table (including `4007`).
  - Socket, API, clock and randomness are injected; the tests use a fake server and fake timers.
- **Rendering:** message bodies are React text only. ESLint forbids `dangerouslySetInnerHTML` and `innerHTML`, and a test renders an HTML payload and finds no element.
- **Not covered:** the fault-injection convergence test (`ConvergenceTest`) runs against the Java reference client, not this one.

## Deployment topology (Phase 6, ADR-0011)
- **`deploy/compose.yml`:**
  - `db` (Postgres 16, a volume, no port);
  - `app` (the `prod` profile, `mem_limit` 768m, a bash `/dev/tcp` healthcheck, no port);
  - `web` (Caddy 2.8: automatic TLS, the built UI, and a reverse proxy for `/api/*`, `/ws` and `/actuator/health`; every other `/actuator*` is 404; publishes 80 and 443).
- **Secrets** come from `deploy/.env` (git-ignored; templates `.env.example` and `.env.local.example`).
- **Local build:** `compose.build.yml` builds both images from source.
- **Phase 4:** an override adds `app2` and `redis` and widens `APP_UPSTREAMS`.
- **Checks:** `deploy/smoke.sh` and the Playwright test in `frontend/e2e/`.
- **Pipeline (ADR-0011 Decisions 8 and 9):** `ci.yml` (backend, frontend, e2e) and `deploy.yml` (manual: gate → GHCR images → `production` approval → SSH with a pinned host key → `deploy/prod/deploy.sh`, a health and functional check with rollback). Daily `backup.sh` cron; `restore.sh` drill. Target: one EC2 `t3.small` in ap-southeast-2 behind `dox-chat.in`; `www.dox-chat.in` and `dox.work.gd` 308-redirect (`REDIRECT_HOSTS`).

## Configuration

All environment-specific configuration comes from environment variables.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/chatplatform` | JDBC connection string |
| `DB_USER` | `chatplatform` | PostgreSQL username |
| `DB_PASSWORD` | `chatplatform` | PostgreSQL password |
| `JWT_SECRET` | none (required) | HMAC-SHA256 signing key, at least 32 bytes. The app refuses to start if it is missing, blank, shorter than 32 bytes, or the old built-in default. |
| `WS_ALLOWED_ORIGINS` | none (required) | Comma-separated exact origins allowed to open `/ws` (scheme, host and port, e.g. `https://app.example.com`). The app refuses to start if it is missing, has a blank entry, or contains `*`. |
| `WS_MAX_TEXT_FRAME_BYTES` | `32768` (`ws.max-text-frame-bytes`) | Largest WebSocket text frame; larger frames are closed with `1009`. Sized for a maximal 4,000-char body written with JSON `\uXXXX` escapes (about 24 KB). Binary frames are limited to 1 KiB and closed with `1003`. |
| `WS_SEND_TIME_LIMIT_MS` | `5000` (`ws.send.time-limit-ms`) | Per-socket send-time limit (decorator) and Tomcat's per-session blocking-send timeout; a socket exceeding it is closed with `4003`. |
| `WS_SEND_BUFFER_BYTES` | `524288` (`ws.send.buffer-bytes`) | Per-socket send buffer limit; exceeding it closes the socket with `4003`. |
| `WS_PUSH_THREADS` | `2` (`ws.push.threads`) | Push executor threads. |
| `WS_PUSH_QUEUE_CAPACITY` | `10000` (`ws.push.queue-capacity`) | Push queue size; when full, pushes are dropped, logged and counted. |
| `BENCH_HIKARI_POOL` | 10 (`bench` profile only) | Hikari pool size for load runs ([BENCHMARKS.md](BENCHMARKS.md)). |
| `METRICS_PASSWORD` | none (`metrics.password`) | Password of the `metrics` user for `/actuator/prometheus` and `/actuator/metrics` (HTTP Basic). Missing or shorter than 16 characters: scraping disabled (fail closed). |
| `WS_HEARTBEAT_INTERVAL` | `PT25S` (`ws.heartbeat.interval`) | How often the server pings every socket. |
| `WS_HEARTBEAT_TIMEOUT` | `PT60S` (`ws.heartbeat.timeout`) | A socket with no pong or frame for this long is closed with `4006`. Startup requires it to be at least twice the interval. |
| `WS_SESSION_MAX_LIFETIME` | `PT15M` (`ws.session.max-lifetime`) | How long a WebSocket may stay open after its handshake; then the server closes it with `4001`. ISO-8601 duration. Startup refuses zero or negative values. |
| `auth.refresh-cookie.secure` | `true` (`false` in `dev` only) | The `Secure` attribute of the `chat_rt` refresh cookie (ADR-0010). |
| `REGISTRATION_INVITE_CODE` | none (`registration.invite-code`) | With `registration.mode=invite` (the default), `POST /api/auth/register` needs this code; unset means registration is closed (`403`). `dev`, the test profiles and `bench` set `registration.mode=open` (ADR-0011). |
| `DEMO_PASSWORD` | none (`demo.password`) | When set (at least 8 characters), seeds `demo1`..`demo3` and a "Demo room" at startup; a changed value resets their password hashes. Public data only. |
| `WS_MAX_SOCKETS_PER_USER` | `10` (`ws.max-sockets-per-user`) | Open sockets per user; the next one is closed with `4007` (ADR-0006 Decision 2a). |
| `ratelimit.*` | register 5/h per IP; login 20/min per IP and 5/min per (IP, username); ticket 30/min per user; failed handshakes 20/min per IP; `max-keys` 100000 | In-memory fixed-window limits, per instance (ADR-0011). |
| `SPRING_PROFILES_ACTIVE` | not set | Set to `dev` for local development: loads a dev-only JWT secret, the local frontend origins (`http://localhost:3000`, `http://localhost:5173`) and verbose SQL logging. Never use `dev` in production. `prod` (graceful shutdown, INFO logs) refuses to start together with `dev` or `bench` (`ProfileGuard`). |

Local run: `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun`. Production must set `JWT_SECRET` to a cryptographically random value and `WS_ALLOWED_ORIGINS` to the real frontend origin(s).

---

## API Surface

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | no | Register (`inviteCode` required in invite mode; checked before conflicts); returns `{user, accessToken}` and sets the `chat_rt` refresh cookie. Rate-limited per IP. |
| POST | `/api/auth/login` | no | Login; returns `{accessToken}` and sets the `chat_rt` cookie. Rate-limited per IP and per (IP, username), before BCrypt. |
| POST | `/api/auth/refresh` | `chat_rt` cookie + allowed `Origin` | Rotate the refresh token; returns `{accessToken}` and a new cookie. A failed refresh clears the cookie (ADR-0010). |
| POST | `/api/auth/logout` | `chat_rt` cookie + allowed `Origin` | Revoke the refresh token and clear the cookie (`204`). |
| POST | `/api/conversations/direct` | yes | Create or get direct conversation |
| POST | `/api/conversations/group` | yes | Create group conversation |
| GET | `/api/conversations` | yes | List user's conversations |
| GET | `/api/conversations/{id}` | yes | Get one conversation |
| POST | `/api/conversations/{id}/messages` | yes | Send message (idempotent) |
| GET | `/api/conversations/{id}/messages` | yes | Paginate messages (keyset): `?before=` newest first, or `?after=` oldest first for catching up (not both); exact `hasMore` |
| GET | `/api/conversations/heads` | yes | `[{conversationId, lastSeq}]` for my active conversations (anti-entropy check, Phase 3) |
| POST | `/api/ws/ticket` | yes | Issue a one-time WebSocket ticket: `{ticket, expiresInSeconds}`. Rate-limited per user. |
| GET (upgrade) | `/ws?ticket=...` | ticket + allowed `Origin` | Open a WebSocket (see Realtime above) |
| POST | `/api/conversations/{id}/cursor` | yes | Move my delivered/read cursor forward: `{kind, seq}` → `{lastDeliveredSeq, lastReadSeq}` (ADR-0007) |
| GET | `/actuator/health` | no | Health check (status only, no details) |
| GET | `/actuator/prometheus`, `/actuator/metrics` | `metrics` user (HTTP Basic) | Micrometer metrics; never public (ADR-0009) |

---

## Behaviour Guarantees (each covered by an integration or unit test)

- **Ordering:** each conversation's `seq` is allocated under a row lock on the conversation. Concurrent senders get unique, contiguous sequence numbers.
- **Cursors:** cursors only move forward and read implies delivered; a stale cursor leaves the row version (`xmin`) unchanged; 8 threads x 25 random updates over 20 rounds always end at the maxima; seq beyond `last_seq`, negative seq and unknown kind give `400` and change nothing; non-member (even with an absurd seq), left participant and unknown conversation give identical `404` (`CursorTest`). Unread counts exclude your own messages and follow the read cursor, in both the list and the single view (`UnreadCountTest`). Over WebSocket, an advanced cursor is pushed to every other socket but not the origin, a stale cursor is not pushed, invalid cursors get an error frame and broken JSON closes `1007`, a non-member with an absurd seq gets the same error frame as an unknown conversation, REST cursors are pushed, and nothing is pushed for a rolled-back cursor (`WsCursorTest`; mutations: publishing when not advanced, and a before-commit listener, each fail it).
- **Convergence under faults (`ConvergenceTest`, seeds 11/22/33; ADR-0008):** 4 users x 2 reference clients send 30 messages each into G1 {U1,U2,U3}, G2 {U2,U3,U4}, D1 {U1,U4} while a seeded injector drops client connections, server sessions (4003/4006), pushes (spy + a 2-slot push queue) and acks, duplicates and delays sends, and fails a seeded 10% of commits once; then the last message of each conversation is committed with every push dropped. Faults stop and, with no forced sync, every client's history equals PostgreSQL (id, seq, senderId, clientMsgId, body) for each conversation it is in, with exactly 243 rows. 5 consecutive runs x 3 seeds: 15/15 converged. Negative variants (seed 11): NO_DEDUPE, NEW_ID_ON_RETRY and NO_ANTI_ENTROPY fail it; FETCH_BEFORE_SUBSCRIBE and NO_GAP_DETECTION are masked by the other layers here and are covered by the targeted tests. A seed reproduces the faults, not the thread schedule.
- **Reference client (test sources, `client/ReferenceClient`; ADR-0008):** deterministic targeted tests, each with a positive and a negative form (the matching bug flag makes it fail): an ack records the client's own message; duplicate pushes are deduplicated by id; subscribe-then-fetch loses nothing committed in between; a push beyond the contiguous seq triggers a gap fetch; anti-entropy recovers a dropped LAST push with no forced sync; an ack timeout resends on the same socket with the same clientMsgId (one row); a protocol-error close stops the client instead of looping; 4001/4003/4006 are followed by a reconnect, 4002 stops for re-login; an expired access token is refreshed exactly once under 8 concurrent requests (`ReferenceClientTest`).
- **Catch-up paging:** a negative, non-numeric or out-of-range `after` gives `400 VALIDATION_ERROR`; `after=` the largest long gives an empty page; `limit` is clamped to 1..100 exactly like `before`. `?after=` returns strictly later messages in ascending order with an exact `hasMore`; `before` + `after` or a negative `after` give `400`; non-members get the usual `404`; list and single conversation responses carry `lastSeq`; `heads` lists only my active conversations; the ascending query can be served by the `(conversation_id, seq)` index with no Sort node (EXPLAIN with sequential scans disabled: it proves the index can serve the query shape, not that the planner always picks it) (`HistoryForwardPagingTest`).
- **Body limit:** 4,000 chars accepted, 4,001 rejected with `400` and nothing stored, over REST and when calling `MessageService` directly (`MessageBodyLimitTest`).
- **Idempotency:** `(sender_id, client_msg_id)` is unique. A retry with the same conversation and body returns the original message. Reusing the key in another conversation or with a different body returns `409 IDEMPOTENCY_KEY_CONFLICT`. The conversation row is locked and the key checked BEFORE the sequence is incremented, so duplicates never create sequence gaps.
- **Refresh tokens:** rotation is a single conditional `UPDATE ... WHERE revoked_at IS NULL ... RETURNING`, so concurrent use of one token yields exactly one success. Replaying a revoked token revokes the whole token family, and that revocation is committed even though the request returns 401 (`noRollbackFor = ApiException`). Family revocation updates only still-active rows (at most one per family), so concurrent replays cannot deadlock: exactly one 200, the rest 401, never a 500 (`RefreshReplayDeadlockTest`).
- **Authorization:** non-members get `404` on every conversation and message endpoint, and nothing is persisted. Unauthenticated requests get `401`.
- **Transactions:** group creation (conversation plus participants) commits or rolls back as one unit; direct-conversation creation is deduplicated by a unique `direct_key`.
- **Errors:** unknown routes return 404, malformed JSON or a malformed UUID returns 400, and unexpected errors return 500 without internals.
- **WebSocket handshake:** the `Origin` header must exactly match an entry in `ws.allowed-origins` (no prefix, case or trailing-slash tolerance) and is checked before the ticket is consumed, so a rejected handshake leaves the ticket usable for a legitimate retry. Every rejection is `401`. Spring's own origin check runs afterwards on the same list as a second layer.
- **WebSocket registry:** every accepted socket is registered under the user id from its ticket and removed on close; adding the first and removing the last socket of a user are atomic (`ConnectionRegistryTest`, `WsConnectionRegistryTest`, `WsHandshakeAuthTest`). A socket with no user id is closed with `1008` (`ChatWebSocketHandlerTest`).
- **Theft close:** replaying a refresh token closes all of that user's sockets with `4002` and no one else's; a normal refresh or logout closes nothing; `closeAll` runs only after the revocation is visible to other connections; `closeAll` runs on a realtime scheduler thread (`ws-sched-*`), never the request thread (`WsTheftCloseTest`, with mutation checks for each).
- **Frame protocol:** unparseable envelope or `clientMsgId` closes with `1007`, `v` other than 1 with `4004`, unknown type with `4005`, over 32 KiB with `1009`; a fully escaped maximal frame (about 24 KB) is accepted; invalid fields get an error frame with the `clientMsgId` and the socket stays open; "not found" and "not a member" errors are identical (`FrameCodecTest`, `WsProtocolTest`).
- **WebSocket send and ack:** the ack's `messageId`, `seq`, `clientMsgId` and `createdAt` equal the stored row; a duplicate send gets an identical ack and one row; non-member and unknown conversation get identical `CONVERSATION_NOT_FOUND` frames and nothing is stored; a 4,001-char body, a blank body and a missing `clientMsgId` get `VALIDATION_ERROR` frames and nothing is stored; if the commit fails, the client gets `INTERNAL_ERROR` and no ack; a `senderId`/`userId` field in the frame is ignored and the message is stored under the handshake user (`WsSendAckTest`; mutation using a forged id as sender fails it; mutation: ack sent inside the transaction makes an ack arrive for a rolled-back send).
- **WebSocket push:** the origin socket gets only the ack, the sender's other sockets and the other participants get the `message` once; duplicates are not pushed again; REST sends are pushed; a participant with `left_at` set gets nothing; a closed recipient does not affect the sender; nothing is pushed for a send whose commit fails; Tomcat's blocking-send timeout is set and read by Tomcat itself (`WsPushTest`). A full push queue drops pushes while every ack still arrives (`WsPushQueueTest`). 6 sockets sending 20 messages each concurrently: seq is exactly 1..120 and each socket gets exactly its 20 acks and the other 100 messages once (`WsConcurrentSendTest`). A stalled consumer is closed with `4003` and unregistered while others still receive (`ConnectionRegistrySendTest`).
- **Heartbeats:** a stale socket is closed with `4006` and unregistered, a fresh one is pinged, a pong or frame keeps it alive, and a timeout below twice the interval refuses startup (`HeartbeatMonitorTest`, fake clock); a real client that answers pings stays open over 3x the timeout while the server counts its pings and pongs (`WsHeartbeatTest`; mutation "pong does not refresh" closes it with 4006, 3 of 3 runs).
- **WebSocket lifetime:** each socket is closed with `4001` its configured lifetime after its own handshake, and then forgotten by the registry (`WsSessionLifetimeTest`, 1-second lifetime); zero, negative or missing lifetimes refuse startup (`ChatWebSocketHandlerTest`).
- **Metrics exposure:** anonymous scrapes, a user's JWT and a wrong Basic password get `401`/`403`; the metrics user's credentials do not work on the API; a password shorter than 16 characters disables scraping; health is unchanged (`MetricsExposureTest`, `MetricsFailClosedTest`; mutations: `permitAll` on the metrics chain, accepting a short password).
- **Application metrics:** each `chat.*` meter moves on its event (REST created/duplicate/rejected counted separately, WS sends counted as `ws` and timed, the sessions gauge follows connects and closes, closes by code, tickets, cursors, heads); a full push queue increments `chat.push.dropped` by exactly the listener's own count; no `chat.*` meter carries a tag outside a fixed set (`ChatMetricsTest`, `WsPushQueueTest`; mutations: a duplicate counted as created, a per-sender tag, a gauge counting users instead of sockets).
- **Actuator:** only `/actuator/health` is public, and it shows no component details.
- **Refresh cookie (ADR-0010):** register and login set `chat_rt` with HttpOnly, Secure, SameSite=Strict, `Path=/api/auth` and a 7-day Max-Age, and no body contains the refresh token; refresh rotates the cookie; refresh or logout with a missing or foreign `Origin` gets `403` and changes nothing; logout clears the cookie (`RefreshCookieTest`).
- **Rate limits (ADR-0011):** the limit-plus-one register, login, ticket or failed-handshake attempt gets `429` with `Retry-After`; another IP, pair or user is unaffected; a limited login never reaches BCrypt; a blocked handshake doesn't consume the ticket; the limiter's memory stays bounded (`FixedWindowRateLimiterTest`, `RateLimitTest`).
- **Registration policy:** without the right invite code, registration gets `403` and stores nothing, and a taken username or email isn't revealed (`RegistrationPolicyTest`, `RegistrationPolicyUnitTest`).
- **Socket cap:** the socket over `ws.max-sockets-per-user` is closed with `4007`, the others stay open, and the cap is per user (`SocketCapTest`).
- **Demo seed and profiles:** seeding is idempotent and a changed demo password replaces the old one (`DemoSeedTest`); `prod` with `dev` or `bench` refuses startup (`ProfileGuardTest`).
- **Accounts:** usernames and emails are case-insensitive (`citext` column plus an explicit `CAST(... AS citext)` in queries, because a plain varchar parameter makes the comparison case-sensitive). Duplicates return `409 USERNAME_TAKEN` / `EMAIL_TAKEN`, including when two registrations race (the unique-constraint violation is translated to the same 409).
- **History paging:** `limit` is clamped to 1..100 (default 50); the cursor is stable when new messages arrive.

## WebSocket Socket Lifetime (decided 2026-11-03; implemented in Phase 2b-ii)

A socket authenticates once, at the handshake, so it could otherwise outlive the 15-minute access token that minted its ticket. Decision: **A + B (theft only)**.
- **A:** the server closes every WebSocket `ws.session.max-lifetime` (default 15 minutes) after its handshake, with close code `4001`; the client reconnects with a fresh ticket (which needs a valid access token). Access revocation therefore takes effect within one token lifetime. Implementation: `ChatWebSocketHandler` schedules the close on the realtime `wsTaskScheduler` at connect and cancels it when the socket closes earlier.
- **B (theft only):** when a refresh-token replay is detected and the family is revoked, all of that user's sockets are closed with `4002`. Flow: `TokenService` publishes `RefreshTokenFamilyRevokedEvent(userId, familyId)` (package `auth.event`); `SessionRevocationListener` (realtime) handles it with `@TransactionalEventListener(AFTER_COMMIT)` and hands `ConnectionRegistry.closeAll` to the realtime scheduler, so the `401` response never waits on socket closes. Design: [ADR-0005](adr/0005-connection-registry-and-after-commit-socket-closing.md).
- Logout does not close sockets in this version (see Known Limitations).

## Per-conversation serialization: a tradeoff for gap-free ordering
*(Phase 5-lite, P5-3: documented only, no code. Decision: [ADR-0009 Decision 5](adr/0009-observability-and-measurement.md#decision-5-keep-per-conversation-serialization-document-the-limit-2026-11-05-after-step-4).)*

**What we have.** Each send allocates its `seq` inside the transaction that inserts the message, while holding the conversation row lock (`SELECT ... FOR UPDATE` → `last_seq + 1`) until commit. Because of that, every committed `seq` is contiguous: no holes and no out-of-order visibility. Phase 3's client protocol relies on exactly this:
- **Gap detection:** a push with `seq > contiguousSeq + 1` triggers a fetch.
- **Anti-entropy:** `heads.lastSeq` versus `contiguousSeq`.
- **Paging:** `?after=` history paging (ADR-0008).

**The price.** Sends to one conversation are serial. On one laptop, one conversation held through 200/s and was unstable at 300/s ([BENCHMARKS.md section 7](BENCHMARKS.md#7-bottleneck-analysis)). Different conversations don't contend with each other.

**Options for relaxing it, and what each costs gap detection:**

| Option | What it buys | Cost to Phase 3 gap detection, and other costs |
|---|---|---|
| **Keep it (current)** | Gap-free, durable, simple | None. One conversation's throughput is bounded by the serial lock hold. |
| **Make the hold cheaper** (faster disk; Postgres group commit) | Same semantics | **None.** Group commit mainly helps many conversations, not one row, because the lock is held until that transaction's flush. |
| **`synchronous_commit=off` for sends** | Shorter hold (whether it's much shorter is the open P5-2 hypothesis) | **Breaks the core guarantee and gap detection.** A crash can lose *acked* messages and roll back `last_seq`, so the same `seq` is reused for a different message. Clients see a conflict, not a gap, and nothing detects it. **Rejected.** |
| **Batch sends per conversation** (one app-side writer commits N queued sends in one transaction, with contiguous `seq`) | One lock and one flush per batch | **None** (still contiguous). Costs: batch-window latency; one bad message mustn't fail the batch; in Phase 4 multi-instance, a single writer per conversation needs routing, otherwise the row lock still arbitrates. |
| **Allocate `seq` outside the transaction** (a Postgres `SEQUENCE`, or reserved blocks of numbers) | No wait on the conversation row | **Breaks it.** Rolled-back or unused numbers leave permanent holes, and commits become visible out of `seq` order. A client can't tell a hole from a slow commit, so it would need a per-conversation "committed through" watermark or tombstones. The guarantee that duplicates never burn a number (ADR-0002) is also lost. |
| **No per-conversation `seq`** (order by a global id or time) | No hot row at all | **Removes it.** Clients would resync by time windows with an overlap for late commits and dedupe by id; `heads` / `lastSeq` / `?after=` change meaning. It's a redesign of ADR-0008. |

## Known Limitations

- **Rate limits are in memory and per instance** (ADR-0011): two instances double them (Phase 4 moves them to Redis), a fixed window can admit up to 2× the limit across a boundary, users behind one NAT share the per-IP limits, and anyone sharing a victim's IP can use up that victim's login attempts. `GET`s and message sends aren't rate-limited.
- **A lost refresh response logs the user out:** if the server rotated the token but the response (with its `Set-Cookie`) never reached the browser, the next refresh replays the old token, which revokes the family and closes the user's sockets with `4002` (ADR-0010). Two tabs refreshing at once would do the same; the UI serializes refresh across tabs.
- **Deploys disconnect everyone:** one app instance restarts on every deploy, so sockets close (`1001`) and clients reconnect and catch up. There's no zero-downtime deploy before Phase 4.
- **Backups live on the same server** until they're copied off by hand; restoring the live database is a manual procedure (runbook 6.2).
- **VPS sizing is derived from memory limits** (2 GB recommended for Phase 6, 4 GB for Phase 4), not measured.
- **The browser UI:** unsent messages live in memory, so a reload loses the outbox (sends that were already acked are safe). It doesn't create conversations, and it shows senders as "You" or a short id (there's no user lookup endpoint). Its sync code is unit-tested, but the convergence-under-faults test covers only the Java reference client.
- **The demo is public data**, and a changed `DEMO_PASSWORD` doesn't revoke existing demo sessions.

- **Metrics use HTTP Basic:** the password travels with every scrape, so production needs TLS in front (Phase 9).
- **Measurements are single-laptop:** the load harness runs on the same machine as the app, and Postgres runs in Docker Desktop's VM. Results describe that setup only ([BENCHMARKS.md](BENCHMARKS.md)). The `bench` profile also exposes `/actuator/env` (values shown to the metrics user only); never use that profile outside local measurement.
- **`heads` is unpaginated:** `GET /api/conversations/heads` returns all of a user's active conversations in one response (no pagination); users with very many conversations will need pagination or a "changed since" variant later. The anti-entropy polling cost (one `heads` call per client every ~30 s) is still unmeasured (a later scenario in [BENCHMARKS.md](BENCHMARKS.md)).
- **Reliable delivery is a client responsibility (ADR-0008):** the reference client lives in test sources (not a published SDK); convergence is shown on one instance with a bounded workload; seeds reproduce faults, not thread schedules; anti-entropy polling cost (`heads` every ~30 s per client) is still unmeasured; there is no server-sent resync hint; a client offline longer than the refresh-token lifetime must log in again.
- **WebSocket pushes are best effort:** they may arrive out of `seq` order (after-commit handoffs can interleave), are dropped when the push queue is full, and a slow socket is closed with `4003`. Clients order by `seq` and recover from history; gap detection and resync are Phase 3.
- **A dropped push is only repaired by history sync:** a duplicate send returns the same ack but is deliberately not pushed again, so if the original push was dropped (full push queue, `4003` slow consumer, closed socket) the other participants only see the message by paging history. Automatic gap detection and resync by `seq` are Phase 3.
- **No per-socket send rate limit:** per-socket (and per-user) send rate limiting is not implemented; a client can send frames as fast as the server processes them.
- **`@TransactionalEventListener` needs a transaction:** events published with no active transaction are silently ignored (no push, no theft close). `MessageService.sendMessage` and the refresh path are transactional; any new publisher must be too.
- **Logout does not close WebSockets (by design for now):** a socket authenticates only at the handshake. The access token has no session id, so the server cannot tell which of a user's sockets belong to the device that logged out. Closing all of them would log out the user's other devices. An open socket therefore survives logout until the about-15-minute socket lifetime ends (see WebSocket socket lifetime below). Tying sockets to a session id would remove this.
- Strict theft detection: a client that submits the same refresh token twice (for example a double-click) is logged out. A short grace window could relax this later.
- Browser refresh-token storage (HttpOnly cookie plus CSRF/Origin protection versus in-memory) is undecided; decide it in Phase 2 when the React client starts.
- The 401 response from the security filter has an empty body; it is not yet in the standard error JSON format.
- Integration tests need Docker. On a machine whose Docker Engine rejects old API versions, Testcontainers 1.20.1 may need a local `~/.docker-java.properties` containing `api.version=1.44`.
- **Read receipts are visible to every participant;** there is no per-user privacy toggle. **"Delivered" is client-reported** (the client says it received the message), not observed by the server.
- **Cursor fan-out (from 2d-lite step c):** a cursor change is pushed to all participants; clients should debounce cursor frames (send only the highest seq, at most about once per second). Large groups may need server-side coalescing later; there is no server-side cursor rate limit yet.
- The 5,000-message test is a correctness check on one developer laptop, not a benchmark. Load measurements (one laptop, not capacity claims) are in [BENCHMARKS.md](BENCHMARKS.md).
- **A hot conversation is bounded by its row lock:** every send to one conversation holds the conversation row from `SELECT ... FOR UPDATE` until commit, so sends to one conversation are serial. Measured on one laptop ([BENCHMARKS.md section 7](BENCHMARKS.md#7-bottleneck-analysis)):
  - **One conversation held through 200/s and was unstable at 300/s:** 1 of 3 runs SATURATED at Hikari pool 10, 3 of 3 at pool 30. Pool 30 didn't help in these runs, and its p99 was higher; the cause wasn't investigated.
  - **The same load over 10 conversations kept up at 300/s.**
  - **Hypothesis, not measured:** the share of the lock hold spent on the commit flush.
  - **Retries:** sends retried after a client ack timeout take the same lock.
  - **No fix in Phase 5-lite.** The tradeoff and the options are in [Per-conversation serialization](#per-conversation-serialization-a-tradeoff-for-gap-free-ordering).

## What Is Not Yet Implemented

| Feature | Planned Phase |
|---|---|
| WebSocket socket lifetime and theft close (2b-ii), message frames and push (2c), presence and read cursors (2d), React UI (2e) | Phase 2 |
| Client retries, sync cursors, reconnect recovery | Phase 3 |
| Multiple instances and Redis pub/sub | Phase 4 |
| Metrics, tracing, load tests | Phase 5 |
| Transactional outbox and notifications | Phase 6 |
| Collaborative editing (Yjs) | Phase 7 |
| Kafka experiment (optional) | Phase 8 |
| Production deployment | Phase 9 |
