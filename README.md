# Chat Platform

A real-time chat backend built as a learning project for backend engineering: a small, correct, well-tested system whose design decisions are written down and defended in [ADRs](docs/adr/). PostgreSQL is the source of truth; WebSockets are only a delivery optimization.

## Status

| Phase | State |
|---|---|
| 0: skeleton, Flyway, CI | Done |
| 1: REST core (auth, conversations, ordered idempotent messages, keyset paging) | Done |
| 2a/2b-i: WebSocket one-time tickets, authenticated handshake | Done |
| 2b-ii: connection registry, socket lifetime, closing sockets on token theft | In progress (registry done) |
| 2c to 2e: message frames and push, presence and read cursors, React UI | Not started |
| 3 onward: reliable delivery, multi-instance, observability, ... | Not started |

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
The REST endpoints are `/api/auth/*` (register, login, refresh, logout), `/api/conversations/*` (direct and group conversations, sending and paging messages), `POST /api/ws/ticket` and `/actuator/health`. Full table: [architecture.md, API Surface](docs/architecture.md#api-surface).

Connecting a WebSocket:
1. `POST /api/ws/ticket` with `Authorization: Bearer <access token>` returns `{"ticket": "...", "expiresInSeconds": 30}`.
2. Within 30 seconds, open `ws://localhost:8080/ws?ticket=<ticket>` from an allowed `Origin`. A ticket works once.
3. A rejected handshake (bad, used or expired ticket, or an origin not on the list) gets HTTP `401`. A rejection for the origin leaves the ticket usable.

The server tracks each open socket per user. Incoming frames are not handled yet; the frame protocol is Phase 2c. Close codes so far: `1008` if a socket somehow has no authenticated user (should not happen).

## What is guaranteed (each covered by tests)
Per-conversation ordering without gaps, idempotent sends (`client_msg_id`), atomic refresh-token rotation with theft detection, `404` for non-members, one-time WebSocket tickets, exact origin checks, and per-user socket tracking. The precise list, with test names: [architecture.md, Behaviour Guarantees](docs/architecture.md#behaviour-guarantees-each-covered-by-an-integration-or-unit-test).

No performance or capacity numbers are claimed. Tests that run on a laptop are correctness checks, not benchmarks.

## Known limitations
The main ones:
- Logout does not close open WebSockets.
- The connection registry is single-instance.
- The 401 from the security filter has an empty body.
- Unread counts include your own messages.

Full list: [architecture.md, Known Limitations](docs/architecture.md#known-limitations).

## Design decisions
- [ADR-0001: Modular monolith](docs/adr/0001-modular-monolith.md)
- [ADR-0002: Message sequencing and idempotency](docs/adr/0002-message-sequencing-and-idempotency.md)
- [ADR-0003: Authentication (JWT + rotating refresh tokens)](docs/adr/0003-authentication-strategy.md)
- [ADR-0004: WebSocket tickets in PostgreSQL](docs/adr/0004-websocket-authentication-tickets.md)
- [ADR-0005: Connection registry and after-commit socket closing](docs/adr/0005-connection-registry-and-after-commit-socket-closing.md)
- [Architecture overview](docs/architecture.md)
