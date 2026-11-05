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
| `WS_HEARTBEAT_INTERVAL` | `PT25S` (`ws.heartbeat.interval`) | How often the server pings every socket. |
| `WS_HEARTBEAT_TIMEOUT` | `PT60S` (`ws.heartbeat.timeout`) | A socket with no pong or frame for this long is closed with `4006`. Startup requires it to be at least twice the interval. |
| `WS_SESSION_MAX_LIFETIME` | `PT15M` (`ws.session.max-lifetime`) | How long a WebSocket may stay open after its handshake; then the server closes it with `4001`. ISO-8601 duration. Startup refuses zero or negative values. |
| `SPRING_PROFILES_ACTIVE` | not set | Set to `dev` for local development: loads a dev-only JWT secret, the local frontend origins (`http://localhost:3000`, `http://localhost:5173`) and verbose SQL logging. Never use `dev` in production. |

Local run: `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun`. Production must set `JWT_SECRET` to a cryptographically random value and `WS_ALLOWED_ORIGINS` to the real frontend origin(s).

---

## API Surface

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | no | Register; returns user + token pair |
| POST | `/api/auth/login` | no | Login; returns token pair |
| POST | `/api/auth/refresh` | no | Rotate refresh token |
| POST | `/api/auth/logout` | no | Revoke refresh token |
| POST | `/api/conversations/direct` | yes | Create or get direct conversation |
| POST | `/api/conversations/group` | yes | Create group conversation |
| GET | `/api/conversations` | yes | List user's conversations |
| GET | `/api/conversations/{id}` | yes | Get one conversation |
| POST | `/api/conversations/{id}/messages` | yes | Send message (idempotent) |
| GET | `/api/conversations/{id}/messages` | yes | Paginate messages (keyset): `?before=` newest first, or `?after=` oldest first for catching up (not both); exact `hasMore` |
| GET | `/api/conversations/heads` | yes | `[{conversationId, lastSeq}]` for my active conversations (anti-entropy check, Phase 3) |
| POST | `/api/ws/ticket` | yes | Issue a one-time WebSocket ticket: `{ticket, expiresInSeconds}` |
| GET (upgrade) | `/ws?ticket=...` | ticket + allowed `Origin` | Open a WebSocket (see Realtime above) |
| POST | `/api/conversations/{id}/cursor` | yes | Move my delivered/read cursor forward: `{kind, seq}` → `{lastDeliveredSeq, lastReadSeq}` (ADR-0007) |
| GET | `/actuator/health` | no | Health check (status only, no details) |

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
- **Actuator:** only `/actuator/health` is public, and it shows no component details.
- **Accounts:** usernames and emails are case-insensitive (`citext` column plus an explicit `CAST(... AS citext)` in queries, because a plain varchar parameter makes the comparison case-sensitive). Duplicates return `409 USERNAME_TAKEN` / `EMAIL_TAKEN`, including when two registrations race (the unique-constraint violation is translated to the same 409).
- **History paging:** `limit` is clamped to 1..100 (default 50); the cursor is stable when new messages arrive.

## WebSocket Socket Lifetime (decided 2026-11-03; implemented in Phase 2b-ii)

A socket authenticates once, at the handshake, so it could otherwise outlive the 15-minute access token that minted its ticket. Decision: **A + B (theft only)**.
- **A:** the server closes every WebSocket `ws.session.max-lifetime` (default 15 minutes) after its handshake, with close code `4001`; the client reconnects with a fresh ticket (which needs a valid access token). Access revocation therefore takes effect within one token lifetime. Implementation: `ChatWebSocketHandler` schedules the close on the realtime `wsTaskScheduler` at connect and cancels it when the socket closes earlier.
- **B (theft only):** when a refresh-token replay is detected and the family is revoked, all of that user's sockets are closed with `4002`. Flow: `TokenService` publishes `RefreshTokenFamilyRevokedEvent(userId, familyId)` (package `auth.event`); `SessionRevocationListener` (realtime) handles it with `@TransactionalEventListener(AFTER_COMMIT)` and hands `ConnectionRegistry.closeAll` to the realtime scheduler, so the `401` response never waits on socket closes. Design: [ADR-0005](adr/0005-connection-registry-and-after-commit-socket-closing.md).
- Logout does not close sockets in this version (see Known Limitations).

## Known Limitations

- **`heads` is unpaginated:** `GET /api/conversations/heads` returns all of a user's active conversations in one response (no pagination); users with very many conversations will need pagination or a "changed since" variant later. The anti-entropy polling cost (one `heads` call per client every ~30 s) is unmeasured until Phase 5.
- **Reliable delivery is a client responsibility (ADR-0008):** the reference client lives in test sources (not a published SDK); convergence is shown on one instance with a bounded workload; seeds reproduce faults, not thread schedules; anti-entropy polling cost (`heads` every ~30 s per client) is unmeasured (Phase 5); there is no server-sent resync hint; a client offline longer than the refresh-token lifetime must log in again.
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
- The 5,000-message test is a correctness check on one developer laptop, not a benchmark; no throughput claim is made until Phase 5.

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
