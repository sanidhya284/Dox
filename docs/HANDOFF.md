# Handoff: where the project is and what to do next

Written 2026-11-03 for Claude Code taking over from a Claude-chat architect + Gemini-in-Antigravity executor workflow. The repo and test output are authoritative; this file is what the previous sessions believed. **Verify before building on any of it** (Section 1).

## 0. Status in one screen

| Area | State |
|---|---|
| Phase 0 (skeleton, Flyway, CI) | Done |
| Phase 1 (REST core: auth, conversations, ordered idempotent messaging, keyset paging) | Done and verified by tests that were seen failing first, then passing. Tag `phase-1-verified` = commit `9004cd1` (pushed). |
| Phase 2a (WebSocket one-time tickets in PostgreSQL) | Done. 9 tests + a mutation proof (a plain SELECT instead of `DELETE ... RETURNING` fails 3 tests). ADR-0004 written. |
| Phase 2b-i (handshake interceptor: Origin check + ticket consume) | Built and tested (10 tests), **hardening not yet applied** (Section 3). |
| Phase 2b-ii onward | Not started. |
| Last full local run | 6 unit + 59 integration tests, all green (counts read from XML). CI integration step went green on PR #2 after the Docker fix; the 59 count in CI was not confirmed from the artifact. |

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
7. `NoOpPlaceholderHandler` keeps raw tickets in a never-cleared map as a test seam. It disappears in 2b-ii when the real handler lands (tests that rely on `getUserIdForTicket` must be reworked then).
Also confirm no code logs the ticket or the request query string. Note for Phase 9: reverse-proxy access logs record full URLs including `?ticket=`; the 30-second single-use design limits the damage.

## 4. Decisions already made (don't relitigate)
- WebSocket handshake auth = one-time ticket in a **PostgreSQL table** (`ws_tickets`: SHA-256 hash PK, user_id, expires_at 30 s), consumed via `DELETE ... RETURNING`. Chosen over in-memory (breaks in Phase 4) and Redis (not in the stack until Phase 4). See ADR-0004. Issue endpoint: `POST /api/ws/ticket` (JWT required) returns `{ticket, expiresInSeconds}`.
- Idempotency/ordering design: ADR-0002 (amended). Auth design: ADR-0003 (amended). Modular monolith: ADR-0001.

## 5. Decisions still OPEN (ask the user; don't pick silently)
1. **Socket lifetime vs the 15-minute access token.** A socket authenticates only at the handshake and can outlive the JWT. Options: **A** server closes every socket after about 15 minutes, client reconnects with a fresh ticket; **B** also close a user's sockets when a refresh-token family is revoked (theft) and on logout; **C** both. The architect's lean: A, plus B for theft only (logout would otherwise close the user's other devices unless sockets are tied to a session id). Not yet answered by the user.
2. Browser refresh-token storage (HttpOnly cookie + CSRF/Origin protection vs in-memory): deferred to Phase 2e, when the React client starts.
3. Testcontainers upgrade path (see CLAUDE.md gotchas).
4. Whether to relax strict theft detection with a short grace window (today a double-submitted refresh logs the user out).

## 6. Remaining Phase 2 plan (each step small enough to test and review)
- **2b-ii: connection registry + real handler.** Replace `NoOpPlaceholderHandler`. Registry maps userId to active sessions (a user can have several tabs/devices); remove on close/error; close sockets per decision 5.1; close on logout/family revocation per the same decision. Tests: multiple sessions per user, cleanup on close, handshake-authenticated user id is the one bound to the session.
- **2c: send, ack, push.** Define a small versioned frame envelope (proposal: `{ "v":1, "type":"...", ...}`) so Phase 3 can add sync cursors without breaking clients. Client sends a message frame reusing the existing `MessageService.sendMessage` (do not duplicate its transaction logic). The **ack is sent only after the PostgreSQL commit**. Recipient push must happen **after commit** (e.g. `TransactionSynchronization.afterCommit`), never inside the transaction, so a rollback can't leak a phantom message. Duplicate sends with the same `clientMsgId` return the same message and don't re-push. Non-members get no delivery and nothing persisted. Tests: ack-after-commit (inject a failure before commit: no ack, no push), duplicate send, authorization, slow/closed recipient doesn't fail the sender.
- **2d: heartbeats and connection cleanup, in-memory presence, typing indicators, delivered/read cursors** (monotonic updates; also fixes the known "unread count includes my own messages" limitation).
- **2e: minimal React UI** and the refresh-token storage decision; then deploy a simple version behind a proxy with TLS to find proxy/CORS/timeout problems early.
- After Phase 2: Phase 3 reliable delivery (client queues, retries with the same idempotency key, backoff with jitter, cursor-based sync, gap detection, bounded outbound queues), Phase 4 Redis multi-instance (pub/sub is not durable; recovery still comes from Postgres), Phase 5 observability and real benchmarks, Phase 6 outbox + notifications, Phase 7 Yjs, Phase 8 optional Kafka, Phase 9 deployment. A fully working Phase 0 to 5 with strong tests and measured results beats an unfinished system with every technology.

## 7. Known limitations (documented in `docs/architecture.md`; keep it current)
- Strict theft detection logs out a legitimately double-submitting client.
- Unread counts include the sender's own messages until read cursors exist (Phase 2d).
- The 401 from the security filter has an empty body, not the standard error JSON.
- No per-user rate limit on ticket issuance.
- The 5,000-message test (100 workers x 50, exactly the contiguous seq range 1..5000, about 17 s on the author's laptop) is a correctness check, not a benchmark; no throughput claim until Phase 5.
- ArchUnit package-boundary tests were never built.

## 8. How the previous workflow failed (so you can avoid it)
A weaker executor model summarized instead of pasting output, once invented a test count, once hid that a run had hung, and twice changed specified SQL silently. Hence: read results from XML and tool output yourself, show failing-first evidence, and keep edits scoped. You can now run commands directly, so there is no paste-back loop. Still keep milestones small, and give the user the short interview-style explanation after each one.
