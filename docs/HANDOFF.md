# Handoff: where the project is and what to do next

Written 2026-11-03 for Claude Code taking over from a Claude-chat architect + Gemini-in-Antigravity executor workflow. The repo and test output are authoritative; this file is what the previous sessions believed. **Verify before building on any of it** (Section 1).

## 0. Status in one screen

| Area | State |
|---|---|
| Phase 0 (skeleton, Flyway, CI) | Done |
| Phase 1 (REST core: auth, conversations, ordered idempotent messaging, keyset paging) | Done and verified by tests that were seen failing first, then passing. Tag `phase-1-verified` = commit `9004cd1` (pushed). |
| Phase 2a (WebSocket one-time tickets in PostgreSQL) | Done. 9 tests + a mutation proof (a plain SELECT instead of `DELETE ... RETURNING` fails 3 tests). ADR-0004 written. |
| Phase 2b-i (handshake interceptor: Origin check + ticket consume) | Done, including the Section 3 hardening. Merged to `main` (PR #3, 2026-11-04). |
| Refresh-token replay deadlock fix | PR #4 (`fix-refresh-deadlock`), CI green, open. Not yet on `main`. |
| Phase 2b-ii (registry, lifetime, theft close; [ADR-0005](adr/0005-connection-registry-and-after-commit-socket-closing.md)) | In progress on branch `phase-2b-ii`. Step (a) registry + real handler: done. Step (b) socket lifetime: done. Step (c) theft close: next. |
| Last full local run (2026-11-04, branch `phase-2b-ii`, after step b) | Unit 20/20 (ChatPlatformApplicationTests 1, ChatWebSocketHandlerTest 5, ConnectionRegistryTest 5, WsAuthHandshakeInterceptorTest 4, JwtServiceTest 5). Integration 68/68 (GroupRollback 2, MessageConcurrency 3, MessageThroughput 1, Phase1Coverage 18, Phase1Review 14, SeqRollback 1, TokenTheft 1, WsConnectionRegistry 1, WsHandshakeAuth 16, WsSessionLifetime 2, WsTicket 9). This branch does not have the PR #4 deadlock fix yet. |

## 1. First task: verify and tidy git (before any feature work)
The repo state was left mid-cleanup. Don't assume; check:
```
git status -sb ; git branch -vv ; git log --oneline --graph --all -15 ; git remote -v ; git tag
```
Known / believed:
- Remote moved to `https://github.com/sanidhya284/Dox.git`; local `origin` may still use the old `chatplatform` URL (works via redirect). Fix with `git remote set-url origin https://github.com/sanidhya284/Dox.git`.
- `main` is at `154f9dd "bin"` on top of `46dcd84 Phase 1` and `60ffaaa Phase 0`. **Unverified:** what `154f9dd` contains. It was probably the IDE build output directory `backend/bin/` committed by mistake. Check with `git show --stat 154f9dd`. If so, stop tracking it (`git rm -r --cached backend/bin`) and make sure `.gitignore` has `backend/bin/` (there may be an uncommitted `.gitignore` edit lying around).
- Two PRs are open into `main`: **#1** `phase1-verification` (tip `9004cd1`, 12 ahead) and **#2** `phase-2` (18 ahead, contains #1 plus Phase 2a/2b-i, ADR-0004, and the CI Docker fix). Both showed green checks. The plan: merge #2 with a **merge commit, not squash** (squash rewrites commit ids and breaks the tag and branch ancestry). #1 should then show as merged; if not, close it with a note. Do not re-run #1's CI: it predates the Docker fix and would fail for the environment, not the code.
- Merge `origin/main` into `phase-2` first (brings in the `bin` commit), add the `backend/bin/` untrack commit there, push, wait for green, then merge #2.
- An accidental local branch `phase-2b` may exist, created from `main` before the merge (empty of work). Delete it: `git branch -d phase-2b`.
- After the merge: `git switch main && git pull`, confirm `git merge-base --is-ancestor 9004cd1 main`, then work on a fresh branch from the merged `main`.
Ask the user before pushing or merging anything.

## 2. Verify the test baseline
With Docker running and `~/.docker-java.properties` containing `api.version=1.44`:
`./gradlew test` and `./gradlew integrationTest`, then read per-class counts from the XML. Expected: 6 unit; 59 integration = GroupRollbackTest 2, MessageConcurrencyTest 3, MessageThroughputTest 1, Phase1CoverageTest 18, Phase1ReviewTest 14, SeqRollbackTest 1, TokenTheftTest 1, WsHandshakeAuthTest 10, WsTicketTest 9. Report any difference.

## 3. Phase 2b-i hardening (pending; do this next, test-first)
Found in code review of `WsAuthHandshakeInterceptor`, `WebSocketConfig`, `SecurityConfig`, `NoOpPlaceholderHandler`:
1. **Origin allowlist fails open.** `ws.allowed-origins` has a hardcoded localhost default in `@Value`. Remove the default; fail startup (like the JWT secret) if the list is empty, contains a blank, or contains `*`. Provide the localhost origins via `application-dev.yml` and the test profiles. Existing allowlist behavior is good: exact `List.contains` match, checked before the ticket is consumed, missing Origin rejected, ticket stays unconsumed on any rejection, all rejections 401.
2. `WebSocketConfig` uses `setAllowedOriginPatterns("*")`, which disables Spring's own origin check. Pass the same explicit list via `setAllowedOrigins(...)` as a second layer. Make sure existing tests keep their status codes.
3. `SecurityConfig` permits `/ws` **and `/ws/**`** without JWT, but only `/ws` exists. Narrow to `/ws`. Add a test that `GET /ws/anything` without auth returns 401.
4. Add near-miss origin tests (follow the existing pattern: rejected handshake, then retry with an allowed origin succeeds, proving the ticket was not consumed): `http://localhost:3000.evil.com`, `http://localhost:3000/`, `http://localhost:3001`, `https://localhost:3000`.
5. Unit test for interceptor construction (empty list, wildcard, blank entry all throw `IllegalStateException` mentioning `ws.allowed-origins`).
6. Real boot checks: with only `JWT_SECRET` set and no profile it must refuse to start; with `SPRING_PROFILES_ACTIVE=dev` it starts; with `WS_ALLOWED_ORIGINS='*'` it refuses.
7. ~~`NoOpPlaceholderHandler` keeps raw tickets in a never-cleared map as a test seam.~~ Resolved in 2b-ii step (a): the handler is deleted, and the tests check the `ConnectionRegistry` (`awaitSessionFor(userId)`) instead.
Also confirm no code logs the ticket or the request query string. Note for Phase 9: reverse-proxy access logs record full URLs including `?ticket=`; the 30-second single-use design limits the damage.

### 3 status (2026-11-03): items 1 to 6 done
- Items 1 and 5: the allowlist has no default and refuses startup if empty, blank or containing `*`. `WsAuthHandshakeInterceptorTest` has 4 unit tests (3 seen failing first). Origins are set in `application-dev.yml`, `application-test.yml` and `application-integration.yml`.
- Item 2: `setAllowedOrigins(list)` replaces `"*"`. Our interceptor still runs first, so rejections stay 401. `assertHandshakeFails` now asserts `[401]`.
- Item 3: only `/ws` is public. `pathsUnderWsAreNotPublic` failed first (404) and now passes (401).
- Item 4: near-miss origins (plus `HTTP://LOCALHOST:3000`) are a parameterized test. Mutation checks: prefix matching fails 2 tests. Turning off our check fails 7, because Spring's check alone lets through a missing Origin, a trailing slash and an uppercase origin.
- Item 6: verified on the real jar. No profile refuses ("ws.allowed-origins is not set"), `WS_ALLOWED_ORIGINS='*'` refuses, and `dev` starts. Live: `/ws/anything` gives 401, and an upgrade from `localhost:3000.evil.com` gives 401.
- No code logs the ticket or the query string. Item 7 stays as written (it goes away in 2b-ii).

## 3a. Open bug found 2026-11-03: concurrent refresh replay deadlock
`Phase1ReviewTest.concurrentRefreshWithSameTokenReturnsOneSuccessAndRevokesFamily` failed once with 1 success and 3 x 401 instead of 4: the fifth request got a 500. The log shows `PSQLException: deadlock detected ... while updating tuple in relation "refresh_tokens"` from `RefreshTokenRepository.revokeFamily` (via `TokenService.refreshTokens`). Two replays each revoke the family and lock its rows in different orders. The same test passed in the baseline run, so the failure is intermittent. Fix test-first: lock the family's rows in a consistent order (e.g. `SELECT ... ORDER BY id FOR UPDATE` before the update), or make `revokeFamily` a single statement that cannot interleave with another, and make the test reliably reproduce the race (more threads or repetitions).

## 4. Decisions already made (don't relitigate)
- WebSocket handshake auth = one-time ticket in a **PostgreSQL table** (`ws_tickets`: SHA-256 hash PK, user_id, expires_at 30 s), consumed via `DELETE ... RETURNING`. Chosen over in-memory (breaks in Phase 4) and Redis (not in the stack until Phase 4). See ADR-0004. Issue endpoint: `POST /api/ws/ticket` (JWT required) returns `{ticket, expiresInSeconds}`.
- Idempotency/ordering design: ADR-0002 (amended). Auth design: ADR-0003 (amended). Modular monolith: ADR-0001.

## 5. Decisions still OPEN (ask the user; don't pick silently)
0. **Duplicate ADR-0004 file:** `docs/adr/0004-webSockets.md` is an older, longer draft (from the WIP commit `697be6c`) of the same decision as `0004-websocket-authentication-tickets.md`. Nothing links to it. Delete it, or keep it and mark it superseded? (Not changed, under the don't-rewrite-history rule.)
1. ~~Socket lifetime vs the 15-minute access token.~~ **Decided 2026-11-03 by the user: A + B (theft only).** The server closes every WebSocket after about 15 minutes, and the client reconnects with a fresh ticket. When a refresh-token replay is detected (family revocation), all of that user's sockets are closed immediately. Logout does **not** close sockets in this version, because the access token carries no session id to tell this device's sockets apart from the user's other devices; this is a known limitation (Section 7). Implemented in 2b-ii, not yet.
2. Browser refresh-token storage (HttpOnly cookie + CSRF/Origin protection vs in-memory): deferred to Phase 2e, when the React client starts.
3. Testcontainers upgrade path (see CLAUDE.md gotchas).
4. Whether to relax strict theft detection with a short grace window (today a double-submitted refresh logs the user out).

## 6. Remaining Phase 2 plan (each step small enough to test and review)
- **2b-ii: connection registry + real handler** (design: [ADR-0005](adr/0005-connection-registry-and-after-commit-socket-closing.md); plan approved 2026-11-04). Three steps, one commit each:
  - (a) ✅ `ConnectionRegistry` (`compute` for register and unregister) + `ChatWebSocketHandler` (1008 when the user id is missing) replacing `NoOpPlaceholderHandler`; `WsHandshakeAuthTest` reworked to `awaitSessionFor(userId)`; concurrent race test.
  - (b) ✅ Socket lifetime: `ws.session.max-lifetime` (default `PT15M`, fail closed on zero, negative or missing), close `4001`; test with `PT1S` in its own context. Real jar (dev, `WS_SESSION_MAX_LIFETIME=PT5S`): closed with `4001 session lifetime reached` after 5,453 ms; `PT0S` refused startup.
  - (c) Theft close: `RefreshTokenFamilyRevokedEvent` from auth, `@TransactionalEventListener(AFTER_COMMIT)` in realtime, which hands `closeAll(userId, 4002)` to an executor (never on the request thread). Tests: A's sockets close and B's don't; normal refresh and logout close nothing; after-commit proven by spying on `closeAll` and reading the family rows over a separate connection (the plain `@EventListener` mutation must fail). Check and report whether Tomcat's `session.close` can block.
- **2c: send, ack, push.** Concurrency note (from 2b-ii review, not yet implemented): sending to one `WebSocketSession` from several threads is unsafe (pushes for different conversations, acks and closes can overlap). Wrap each session in `ConcurrentWebSocketSessionDecorator` with a send-time limit and a buffer-size limit (a slow consumer gets closed instead of blocking senders or growing memory), and keep raw sessions inside the registry: callers ask the registry to send, they never receive a session.
  Design: Define a small versioned frame envelope (proposal: `{ "v":1, "type":"...", ...}`) so Phase 3 can add sync cursors without breaking clients. Client sends a message frame reusing the existing `MessageService.sendMessage` (do not duplicate its transaction logic). The **ack is sent only after the PostgreSQL commit**. Recipient push must happen **after commit** (e.g. `TransactionSynchronization.afterCommit`), never inside the transaction, so a rollback can't leak a phantom message. Duplicate sends with the same `clientMsgId` return the same message and don't re-push. Non-members get no delivery and nothing persisted. Tests: ack-after-commit (inject a failure before commit: no ack, no push), duplicate send, authorization, slow/closed recipient doesn't fail the sender.
- **2d: heartbeats and connection cleanup, in-memory presence, typing indicators, delivered/read cursors** (monotonic updates; also fixes the known "unread count includes my own messages" limitation).
- **2e: minimal React UI** and the refresh-token storage decision; then deploy a simple version behind a proxy with TLS to find proxy/CORS/timeout problems early.
- After Phase 2: Phase 3 reliable delivery (client queues, retries with the same idempotency key, backoff with jitter, cursor-based sync, gap detection, bounded outbound queues), Phase 4 Redis multi-instance (pub/sub is not durable; recovery still comes from Postgres), Phase 5 observability and real benchmarks, Phase 6 outbox + notifications, Phase 7 Yjs, Phase 8 optional Kafka, Phase 9 deployment. A fully working Phase 0 to 5 with strong tests and measured results beats an unfinished system with every technology.

## 7. Known limitations (documented in `docs/architecture.md`; keep it current)
- Logout does not close the user's open WebSockets (no session id in the access token). They still close within about 15 minutes (decision 5.1, from 2b-ii).
- Strict theft detection logs out a legitimately double-submitting client.
- Unread counts include the sender's own messages until read cursors exist (Phase 2d).
- The 401 from the security filter has an empty body, not the standard error JSON.
- No per-user rate limit on ticket issuance.
- The 5,000-message test (100 workers x 50, exactly the contiguous seq range 1..5000, about 17 s on the author's laptop) is a correctness check, not a benchmark; no throughput claim until Phase 5.
- ArchUnit package-boundary tests were never built.

## 8. How the previous workflow failed (so you can avoid it)
A weaker executor model summarized instead of pasting output, once invented a test count, once hid that a run had hung, and twice changed specified SQL silently. Hence: read results from XML and tool output yourself, show failing-first evidence, and keep edits scoped. You can now run commands directly, so there is no paste-back loop. Still keep milestones small, and give the user the short interview-style explanation after each one.
