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
| 2e: React UI (presence and typing deferred) | Not started |
| 3: reliable delivery and recovery (catch-up APIs, reference client, convergence under faults) | Done (PR open) |
| 4 onward: multi-instance, observability, ... | Not started |

Details and the next steps: [docs/HANDOFF.md](docs/HANDOFF.md).

## Stack
Java 21, Spring Boot 3.3, Gradle 8.10, PostgreSQL 16, Flyway, Spring JDBC (`JdbcClient`, explicit SQL), HikariCP, Spring Security with JWT (HS256) and BCrypt, Testcontainers, JUnit 5, GitHub Actions. `frontend/` and `load-tests/` are placeholders.

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

Optional: `WS_SESSION_MAX_LIFETIME` (ISO-8601 duration, default `PT15M`) is how long a WebSocket may stay open; zero or negative values refuse startup. `WS_MAX_TEXT_FRAME_BYTES` (default `32768`) is the largest text frame accepted. Heartbeats: `WS_HEARTBEAT_INTERVAL` (default `PT25S`) and `WS_HEARTBEAT_TIMEOUT` (default `PT60S`, must be at least twice the interval). Push tuning: `WS_SEND_TIME_LIMIT_MS` (default `5000`) and `WS_SEND_BUFFER_BYTES` (default `524288`) bound how long and how much a single socket may lag before it is closed with `4003`; `WS_PUSH_THREADS` (default `2`) and `WS_PUSH_QUEUE_CAPACITY` (default `10000`) size the push executor.

All configuration: [architecture.md, Configuration](docs/architecture.md#configuration).

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

## API and WebSocket
The REST endpoints are `/api/auth/*` (register, login, refresh, logout), `/api/conversations/*` (direct and group conversations, sending and paging messages; a message body is at most 4,000 characters, longer bodies get `400 VALIDATION_ERROR`), `GET /api/conversations/{id}/messages?after={seq}&limit={n}` (catch up: messages after a seq, oldest first, exact `hasMore`; `?before=` pages backwards), `GET /api/conversations/heads` (`[{conversationId, lastSeq}]` for your conversations: a cheap "am I behind?" check), `POST /api/conversations/{id}/cursor` (`{"kind":"read"|"delivered","seq":42}`: moves your cursor forward, never back; read implies delivered), `POST /api/ws/ticket` and `/actuator/health`. A conversation's `unreadCount` counts messages after your read cursor that *others* sent; `GET /api/conversations/{id}` also returns every participant's `lastReadSeq` / `lastDeliveredSeq`, and the list returns `myLastReadSeq`. Both also return `lastSeq`, the conversation's newest seq. Full table: [architecture.md, API Surface](docs/architecture.md#api-surface).

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
| `4004` | Unsupported protocol version (`v` is not 1) | Fix the client |
| `4005` | Unknown frame `type` | Fix the client |
| `1007` | Frame is not valid protocol JSON (envelope, or a `clientMsgId` that is not a UUID) | Fix the client |
| `1009` | Frame larger than `WS_MAX_TEXT_FRAME_BYTES` | Fix the client |
| `1003` | Binary frame (not part of the protocol) | Fix the client |
| `1008` | No authenticated user on the socket (should not happen) | Treat as a bug; get a new ticket |

## Writing a client
Pushes are an optimisation; PostgreSQL is the truth. A correct client (full algorithm and reasoning: [ADR-0008](docs/adr/0008-client-sync-protocol.md); a working reference implementation is in `backend/src/test/java/com/chatplatform/client/ReferenceClient.java`):
1. Give each send a `clientMsgId` once and keep it for every retry; resend if no ack arrives within a timeout and after every reconnect. Treat the ack as your own copy of the message.
2. Reconnect with a fresh ticket and exponential backoff with full jitter. On `4002` log in again; on `1007`/`4004`/`4005` stop (it is a client bug); otherwise reconnect.
3. **Subscribe first, then fetch:** open the socket, then page `GET .../messages?after=<your contiguous seq>` where `GET /api/conversations/heads` says you are behind.
4. Deduplicate by message id, order by `seq`; if a push skips ahead of your contiguous seq, fetch the gap.
5. Every ~30 s (jittered) compare `heads` with your contiguous seq and fetch if behind: this is the only way to notice that the *last* push was lost.
6. On a 401, refresh the access token once (shared by concurrent requests) and retry.

`ConvergenceTest` checks this end to end: 8 clients under seeded faults (dropped connections, pushes and acks, duplicated and delayed sends, failed commits) all end with exactly the database's history.

## What is guaranteed (each covered by tests)
Per-conversation ordering without gaps, idempotent sends (`client_msg_id`), a 4,000-character body limit, atomic refresh-token rotation with theft detection, `404` for non-members, one-time WebSocket tickets, exact origin checks, per-user socket tracking, acks only after commit, pushes only after commit and exactly once per message per socket, socket lifetime, and closing a user's sockets after a refresh-token replay (only once the revocation has committed). The precise list, with test names: [architecture.md, Behaviour Guarantees](docs/architecture.md#behaviour-guarantees-each-covered-by-an-integration-or-unit-test).

Under seeded faults, reference clients converge to exactly the stored history (one instance, bounded workload; [ADR-0008](docs/adr/0008-client-sync-protocol.md)). No performance or capacity numbers are claimed. Tests that run on a laptop are correctness checks, not benchmarks.

## Known limitations
The main ones:
- Logout does not close open WebSockets.
- The connection registry and pushes are single-instance (Phase 4: Redis fan-out).
- Pushes may arrive out of `seq` order and may be dropped when the push queue is full; clients recover from history.
- No per-socket send rate limit yet.
- The 401 from the security filter has an empty body.
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
- [Architecture overview](docs/architecture.md)
