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
- Send order inside one transaction: membership check, `SELECT ... FOR UPDATE` on the conversation row, idempotency lookup, then `UPDATE conversations SET last_seq = last_seq + 1 RETURNING last_seq`, then insert. The row lock serialises concurrent senders; because the idempotency check happens before the increment, duplicates never burn a sequence number. `MAX(seq)+1` is unsafe under concurrency (see ADR-0002).
- Idempotency: `client_msg_id` is client-generated. A retry with the same conversation and body returns the original message. Reusing the key in another conversation or with a different body returns `409 IDEMPOTENCY_KEY_CONFLICT`. `UNIQUE(sender_id, client_msg_id)` stays as the database backstop; if an insert still conflicts, the request returns 409 rather than another message.
- Pagination: keyset (`WHERE seq < :cursor ORDER BY seq DESC LIMIT :n`) — O(LIMIT) cost regardless of conversation size.

### Realtime (WebSocket, `realtime` package)
- **Tickets:** `POST /api/ws/ticket` (JWT required) issues a one-time, 30-second ticket stored as a SHA-256 hash in `ws_tickets` ([ADR-0004](adr/0004-websocket-authentication-tickets.md)).
- **Handshake:** `WsAuthHandshakeInterceptor` checks `Origin` exactly against `ws.allowed-origins`, then consumes the ticket with `DELETE ... RETURNING`, and puts the user id in the session attributes. Any rejection is `401`, and the ticket stays usable.
- **`ChatWebSocketHandler`:** registers each accepted socket in `ConnectionRegistry` under that user id, and unregisters it on close or transport error. A socket without a user id (should not happen) is closed with `1008` and not registered. Every socket is closed with `4001` when its lifetime ends (see WebSocket Socket Lifetime). Incoming frames are ignored until the frame protocol (Phase 2c).
- **`ConnectionRegistry`:** in-memory `userId → set of sessions`, so one user can have several tabs or devices. `register` and `unregister` run inside `ConcurrentHashMap.compute`, so adding a user's first socket and removing their last cannot interleave and lose a socket. It exposes session ids only, never raw sessions. It is single-instance; Phase 4 needs cross-instance fan-out. See [ADR-0005](adr/0005-connection-registry-and-after-commit-socket-closing.md).

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
| GET | `/api/conversations/{id}/messages` | yes | Paginate messages (keyset) |
| POST | `/api/ws/ticket` | yes | Issue a one-time WebSocket ticket: `{ticket, expiresInSeconds}` |
| GET (upgrade) | `/ws?ticket=...` | ticket + allowed `Origin` | Open a WebSocket (see Realtime above) |
| GET | `/actuator/health` | no | Health check (status only, no details) |

---

## Behaviour Guarantees (each covered by an integration or unit test)

- **Ordering:** each conversation's `seq` is allocated under a row lock on the conversation. Concurrent senders get unique, contiguous sequence numbers.
- **Idempotency:** `(sender_id, client_msg_id)` is unique. A retry with the same conversation and body returns the original message. Reusing the key in another conversation or with a different body returns `409 IDEMPOTENCY_KEY_CONFLICT`. The conversation row is locked and the key checked BEFORE the sequence is incremented, so duplicates never create sequence gaps.
- **Refresh tokens:** rotation is a single conditional `UPDATE ... WHERE revoked_at IS NULL ... RETURNING`, so concurrent use of one token yields exactly one success. Replaying a revoked token revokes the whole token family, and that revocation is committed even though the request returns 401 (`noRollbackFor = ApiException`).
- **Authorization:** non-members get `404` on every conversation and message endpoint, and nothing is persisted. Unauthenticated requests get `401`.
- **Transactions:** group creation (conversation plus participants) commits or rolls back as one unit; direct-conversation creation is deduplicated by a unique `direct_key`.
- **Errors:** unknown routes return 404, malformed JSON or a malformed UUID returns 400, and unexpected errors return 500 without internals.
- **WebSocket handshake:** the `Origin` header must exactly match an entry in `ws.allowed-origins` (no prefix, case or trailing-slash tolerance) and is checked before the ticket is consumed, so a rejected handshake leaves the ticket usable for a legitimate retry. Every rejection is `401`. Spring's own origin check runs afterwards on the same list as a second layer.
- **WebSocket registry:** every accepted socket is registered under the user id from its ticket and removed on close; adding the first and removing the last socket of a user are atomic (`ConnectionRegistryTest`, `WsConnectionRegistryTest`, `WsHandshakeAuthTest`). A socket with no user id is closed with `1008` (`ChatWebSocketHandlerTest`).
- **WebSocket lifetime:** each socket is closed with `4001` its configured lifetime after its own handshake, and then forgotten by the registry (`WsSessionLifetimeTest`, 1-second lifetime); zero, negative or missing lifetimes refuse startup (`ChatWebSocketHandlerTest`).
- **Actuator:** only `/actuator/health` is public, and it shows no component details.
- **Accounts:** usernames and emails are case-insensitive (`citext` column plus an explicit `CAST(... AS citext)` in queries, because a plain varchar parameter makes the comparison case-sensitive). Duplicates return `409 USERNAME_TAKEN` / `EMAIL_TAKEN`, including when two registrations race (the unique-constraint violation is translated to the same 409).
- **History paging:** `limit` is clamped to 1..100 (default 50); the cursor is stable when new messages arrive.

## WebSocket Socket Lifetime (decided 2026-11-03; A implemented in 2b-ii step b, B in progress)

A socket authenticates once, at the handshake, so it could otherwise outlive the 15-minute access token that minted its ticket. Decision: **A + B (theft only)**.
- **A:** the server closes every WebSocket `ws.session.max-lifetime` (default 15 minutes) after its handshake, with close code `4001`; the client reconnects with a fresh ticket (which needs a valid access token). Access revocation therefore takes effect within one token lifetime. Implementation: `ChatWebSocketHandler` schedules the close on the realtime `wsTaskScheduler` at connect and cancels it when the socket closes earlier.
- **B (theft only):** when a refresh-token replay is detected and the family is revoked, all of that user's sockets are closed immediately.
- Logout does not close sockets in this version (see Known Limitations).

## Known Limitations

- **Logout does not close WebSockets (by design for now):** a socket authenticates only at the handshake. The access token has no session id, so the server cannot tell which of a user's sockets belong to the device that logged out. Closing all of them would log out the user's other devices. An open socket therefore survives logout until the about-15-minute socket lifetime ends (see WebSocket socket lifetime below). Tying sockets to a session id would remove this.
- **Concurrent refresh-token replay can deadlock (open bug):** when several requests replay the same revoked refresh token at once, two `revokeFamily` updates can lock the family's rows in different orders. PostgreSQL then aborts one of them and that request returns `500` instead of `401`. Seen intermittently in `Phase1ReviewTest.concurrentRefreshWithSameTokenReturnsOneSuccessAndRevokesFamily` (2026-11-03). Not fixed yet.
- Strict theft detection: a client that submits the same refresh token twice (for example a double-click) is logged out. A short grace window could relax this later.
- Browser refresh-token storage (HttpOnly cookie plus CSRF/Origin protection versus in-memory) is undecided; decide it in Phase 2 when the React client starts.
- The 401 response from the security filter has an empty body; it is not yet in the standard error JSON format.
- Integration tests need Docker. On a machine whose Docker Engine rejects old API versions, Testcontainers 1.20.1 may need a local `~/.docker-java.properties` containing `api.version=1.44`.
- Unread counts include the sender's own messages, because nothing advances `last_read_seq` yet. Phase 2 (read cursors) owns this.
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
