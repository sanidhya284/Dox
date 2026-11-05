# CLAUDE.md — Real-Time Collaborative Chat Platform

Read this first, every session. The current task state and plan live in `docs/HANDOFF.md`. Read that when starting a milestone, not on every turn.

## Who you're working with
A final-year CS student building this for backend internships and placements. The goal is NOT the most features. The goal is a small, correct, well-tested system whose design decisions they can explain and defend in interviews. So:
- Keep milestones small, and test-first where a guarantee is involved.
- After each milestone, give a short "why we did it this way, and the tradeoff" note (5 to 10 lines), written for interview prep.
- Never invent benchmark numbers or capacity claims. A test that runs on a laptop is a correctness check, not a benchmark.
- They have a limited AI budget. Be economical: plan before big changes, iterate with `--tests` filters, run the full suites once per milestone, don't re-read large files you already read.

## Core principle
**PostgreSQL is the source of truth. WebSockets are a delivery optimization, not the persistence or recovery mechanism.** An acknowledged message is durably committed. A lost push never means a lost message; clients recover from the database.

## Stack and layout
Java 21, Spring Boot 3.3.4, Gradle 8.10.2, PostgreSQL 16, Flyway (V1 to V6), Spring JDBC `JdbcClient` with explicit SQL, HikariCP, Spring Security + JWT (HS256) + BCrypt(12), Testcontainers 1.20.1 (real Postgres), JUnit 5, GitHub Actions. Git root is `chatplatform/`; code is in `backend/`, docs in `docs/` (ADRs 0001 to 0008 + `architecture.md`), CI in `.github/workflows/ci.yml`. The GitHub repo is `sanidhya284/Dox` (the old `chatplatform` URL redirects).

Modular monolith, packages under `com.chatplatform`: `shared`, `user`, `auth`, `conversation`, `message`, `realtime` (tickets + websocket), `health`. A domain's service must not call another domain's repository; go through that domain's service.

## Commands (run from `backend/`)
- Unit tests (H2, fast): `./gradlew test`
- Integration tests (needs Docker running): `./gradlew integrationTest` (about 1 minute). `test` excludes `**/integration/**`; CI runs both.
- One class: `./gradlew integrationTest --tests '*WsTicketTest*'`
- Run the app locally: `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun` (Postgres on localhost:5432 via docker compose). Without `dev` or a `JWT_SECRET` env var the app refuses to start, on purpose.
- Read results from `build/test-results/{test,integrationTest}/*.xml`, never from memory. Report counts you actually read.
- Wrap long Gradle runs in a timeout, and never run two Gradle builds at once (they fight over `build/`).

## Environment gotchas
- **Docker 29 vs Testcontainers 1.20.1:** Docker Engine 29 requires API >= 1.44 but Testcontainers 1.20.1 asks for 1.32, so container startup fails with HTTP 400. Local fix: `echo "api.version=1.44" > ~/.docker-java.properties`. CI has the same line as a workflow step. A proper Testcontainers upgrade is a separate, tested change (sources disagree whether 1.21.3 or 2.x is needed; 2.x renames modules, so verify locally first).
- Integration tests use ONE singleton Postgres container (static block in `AbstractIntegrationTest`). Do not use `@Container` there: it stops the container per class while Spring caches the context, and later classes fail with "connection refused".
- Tests share one database, so use fresh users and conversations per test and never assume empty tables.

## Hard rules
1. NEVER edit an applied Flyway migration (V1 to V6). Schema changes = a new migration.
2. Don't claim a test passed unless you ran it and read the result. Don't report counts from memory.
3. Guarantee tests must be shown capable of failing: after writing one, break the implementation (mutation check) or run the test before the fix, and show it fail, then restore. Several of this project's bugs were found only this way.
4. Fix confirmed defects with a failing test first. No unrelated refactors, renames, or new dependencies.
5. Preserve uncommitted work; commit per milestone on a branch; don't force-push, and push only when asked.
6. If a test fails because of real main-code behavior, say so plainly. Don't weaken the assertion.

## Hard-won lessons (each was a real bug here; each has a test)
- **Timestamps:** bind `OffsetDateTime.ofInstant(i, ZoneOffset.UTC)`, never `java.time.Instant`, as JDBC params (the PG driver can't infer the type; it caused 500 on registration).
- **citext:** a `varchar` parameter makes `citext_col = :p` case-sensitive. Use `CAST(:p AS citext)`. Registration races are translated from `DuplicateKeyException` to 409.
- **Message send order (inside one `@Transactional`):** membership check, then `SELECT ... FOR UPDATE` on the conversation row, then idempotency lookup, then `UPDATE ... last_seq + 1 RETURNING`, then insert. Looking up the key before the increment prevents burned sequence numbers. The same `client_msg_id` with a different conversation or body returns `409 IDEMPOTENCY_KEY_CONFLICT`, never another message. The unique `(sender_id, client_msg_id)` is the backstop.
- **Atomic claims:** one-time things (refresh rotation, WS tickets) are claimed with one conditional statement (`UPDATE ... WHERE revoked_at IS NULL ... RETURNING`, `DELETE ... RETURNING`), so concurrent callers get exactly one winner.
- **Rollback trap:** `ApiException` is a `RuntimeException`. If a state change must persist even though the request returns 401 (family revocation on token replay), use `@Transactional(noRollbackFor = ApiException.class)` on the refresh path.
- **Authorization:** non-members get 404 (not 403) on every conversation and message endpoint, and nothing is persisted. Unauthenticated requests get 401 (`HttpStatusEntryPoint`). Only `/actuator/health` is public in actuator, with no details.
- **`hasMore`** is exact: fetch `limit + 1`, trim. History limit is clamped to 1..100.
- **JWT secret fails closed:** missing, blank, < 32 bytes, or the old default means startup refuses. `dev` and test profiles provide dev-only secrets.
- **Concurrency tests:** create latches before submitting tasks, use `submit` + `get(timeout)`, never `invokeAll` with a start gate (that deadlocked once and froze the run for 11 minutes). Keep `@Timeout` on classes.

## Style
Explicit SQL in repositories. Domain errors via `ApiException(ErrorCode.X)` handled in `GlobalExceptionHandler` (also covers unknown route 404, malformed JSON/UUID 400). Keep ADRs and `docs/architecture.md` in sync with behavior when behavior changes.

## Documentation rule (standing; applies to every milestone)
- **Docs are part of "done".** Any change to behavior, config, commands, endpoints, close codes, error codes, limitations or phase status updates the affected docs **in the same commit** as the code: `README.md`, `docs/architecture.md`, the relevant ADR, and `docs/HANDOFF.md`.
- **README.md** must stay accurate for someone cloning the repo cold: what the project is, current phase status, how to run it (dev profile; `JWT_SECRET` / `WS_ALLOWED_ORIGINS` otherwise), how to run unit and integration tests (including the Docker 29 `api.version` workaround), the API and WebSocket surface, the guarantees actually covered by tests, known limitations, and links to the ADRs. No unmeasured performance or capacity claims.
- **docs/HANDOFF.md:** tick off finished steps, record new decisions, and keep "open decisions" and "known limitations" current, so a fresh session never has to rediscover state.
- **ADRs:** a new ADR for each significant design decision. Amend an existing ADR only when its decision changed, with a dated note; don't rewrite history. Implementation progress goes in HANDOFF, not in ADRs.
- At the end of each step, the report includes a short **"docs touched"** list and says plainly if a doc still needs a human decision.
- Keep docs short and factual. Don't duplicate content between files; link instead.
