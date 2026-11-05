# Interview Notes

Short, honest notes per milestone: the problem, what actually went wrong (or could have), the test that proves the fix, and the tradeoff. Facts come from the code, the [ADRs](adr/) and test results recorded in commits. There are no performance claims: tests on a laptop are correctness checks.

---

## Phase 1 — REST core: auth, conversations, ordered idempotent messages

**Problem.** Messages need a total order per conversation with no gaps, safe retries (idempotency), JWT auth with refresh, and no leaking of other people's conversations.

**What we found.**
- Binding `java.time.Instant` as a JDBC parameter caused a 500 on registration; the PostgreSQL driver can't infer the type. Every integration test registers users, so this is covered everywhere.
- A `varchar` parameter compared with a `citext` column is case-sensitive, so the case-insensitive login and the duplicate-email check silently broke.
- Looking up the idempotency key *after* incrementing `last_seq` burns sequence numbers on duplicate sends.
- The first refresh rotation (SELECT, then UPDATE) let five concurrent requests with the same token all succeed (ADR-0003).
- Revoking a token family on replay was rolled back, because the request throws an `ApiException` (a `RuntimeException`) to return 401.

**Tests.**
- `Phase1CoverageTest`: `loginIsCaseInsensitiveOnUsername`, `duplicateEmailDifferingOnlyInCaseReturns409`, `concurrentRegistrationOfSameUsernameYieldsExactlyOneCreated`, `hasMoreIsFalseWhenLastPageIsExactlyFull`.
- `MessageConcurrencyTest`: `duplicateConcurrentSendsLeaveNoSequenceGap`.
- `SeqRollbackTest`: `sendFailureAfterSeqAllocationLeavesNoGap`.
- `Phase1ReviewTest`: `concurrentRefreshWithSameTokenReturnsOneSuccessAndRevokesFamily`, `nonMemberSendPersistsNothing`.
- `TokenTheftTest`.

**Fix.**
- Bind `OffsetDateTime` in UTC.
- `CAST(:p AS citext)`.
- Send order inside one transaction: membership → `SELECT ... FOR UPDATE` on the conversation → idempotency lookup → `UPDATE last_seq + 1 RETURNING` → insert.
- Refresh rotation as a single conditional `UPDATE ... WHERE revoked_at IS NULL RETURNING`.
- `@Transactional(noRollbackFor = ApiException.class)` on the refresh path.

**Tradeoff.** The conversation row lock serializes senders in one conversation. Strict theft detection logs out a client that double-submits a refresh.

**Follow-ups.**
- *Why not `MAX(seq) + 1`?* Two concurrent senders read the same max and collide or leave gaps. The row lock plus `last_seq` makes allocation serial and gap-free (ADR-0002).
- *Why 404 instead of 403 for non-members?* A 403 confirms the conversation exists. A 404 doesn't tell an outsider anything.
- *Why SHA-256 for refresh tokens, not BCrypt?* They're long random values, not passwords, so there's nothing to brute-force. We need a fast deterministic hash to look them up (ADR-0003).

---

## Phase 2a / 2b-i — WebSocket tickets and handshake hardening

**Problem.** Browsers can't set an `Authorization` header on a WebSocket handshake, and a JWT in the URL leaks into logs.

**Risk found.**
- The origin allowlist had a hardcoded localhost default, so it **failed open**.
- Spring's own origin check was disabled with `"*"`.
- Security permitted `/ws/**`, not just `/ws`.
- Later, CI failed once because a test read server state right after `connect()` returned, before the server had run `afterConnectionEstablished`.

**Tests.**
- `WsTicketTest`: replacing `DELETE ... RETURNING` with a plain `SELECT` fails 3 tests.
- `WsAuthHandshakeInterceptorTest`: empty, wildcard or blank allowlists refuse startup.
- `WsHandshakeAuthTest`: near-miss origins, `pathsUnderWsAreNotPublic`, every rejection asserted as 401.

**Fix.**
- A one-time ticket stored as a SHA-256 hash in PostgreSQL, consumed atomically with `DELETE ... RETURNING` (ADR-0004).
- An exact-match allowlist checked *before* the ticket is consumed, failing closed.
- Spring's check runs on the same list as a second layer.
- The test polls instead of reading once.

**Tradeoff.** One small write and one delete per connection; expired tickets are cleaned up only opportunistically.

**Follow-ups.**
- *Why PostgreSQL, not Redis or memory?* Memory breaks with several instances. Redis would make authentication depend on a component we otherwise let fail soft. PostgreSQL is already the one hard dependency (ADR-0004).
- *Isn't Spring's origin check enough?* No. Turning ours off in a mutation showed Spring's alone lets through a missing Origin, a trailing slash and an uppercase origin.
- *Why check the origin before consuming the ticket?* So a rejected attempt doesn't burn the user's ticket; a retry from the right origin still works.

---

## Fix — refresh-token replay deadlock

**Problem.** Concurrent replays of one refresh token sometimes returned **500** ("deadlock detected" in `revokeFamily`).

**Evidence.** `RefreshReplayDeadlockTest` (30 rounds × 8 concurrent refreshes after 5 rotations) failed 132 of 150 rounds before the fix and 0 of 900 after.

**Fix.** `revokeFamily` updates only active rows (`AND revoked_at IS NULL`). Rotation keeps at most one active token per family, so the statement locks at most one row.

**Tradeoff.** Correctness now relies on the "one active token per family" invariant. A partial unique index to enforce it (V7) is in the backlog.

**Follow-ups.**
- *Why not just lock rows in a fixed order?* We tried. Ordered locking of all rows still failed 53 of 60 rounds; skipping revoked rows fixed it. The exact lock cycle is recorded as a hypothesis in ADR-0003.
- *Why not retry on SQLSTATE 40P01?* A retry hides the cause and would hide a regression too.
- *How do you know the test can fail?* It failed on the old code, and on the ordered-locking variant.

---

## Phase 2b-ii — connection registry, socket lifetime, theft close

**Problem.**
- The server must know each user's open sockets (several tabs or devices).
- A socket authenticates once, so it can outlive the 15-minute access token.

**Bug found.** A two-step registry ("add, then remove the key if the set looks empty") loses sockets under concurrency. `ConnectionRegistryTest` caught that version in 6 of 6 runs (13 to 28 lost registrations per run).

**Tests.**
- `ConnectionRegistryTest`.
- `WsSessionLifetimeTest`: close 4001 after the lifetime.
- `WsTheftCloseTest`, which checks that a token replay closes all of that user's sockets with 4002, that this happens only after commit, and that the close runs on a realtime thread, not the request thread. Mutations: a before-commit listener; closing on the request thread.

**Fix.**
- `ConcurrentHashMap.compute` for both register and unregister.
- A scheduled per-socket close with 4001.
- auth publishes `RefreshTokenFamilyRevokedEvent`; realtime handles it after commit (`@TransactionalEventListener(AFTER_COMMIT)`) on its own scheduler (ADR-0005).

**Tradeoff.**
- Sockets live in memory on one instance.
- Logout does not close sockets, because the token has no session id.
- Tomcat's close can block (by default up to 50 ms per socket for our codes).

**Follow-ups.**
- *Why an event instead of auth calling realtime?* auth shouldn't depend on realtime; realtime depends only on the event type.
- *Why after commit?* The revocation commits even though the request answers 401. Closing earlier could close sockets for a revocation that rolled back.
- *Why not close sockets on logout?* Without a session id in the token we can't tell which device logged out, so closing them all would log out other devices.

---

## Phase 2c — frame protocol, send and ack, push

**Problem.** Send messages over the socket with "ack means committed", and push them to everyone else without blocking or losing correctness.

**Bugs and risks found.**
- **Frame size:** Tomcat's 8 KiB frame limit rejected valid messages, because a client may escape every character as `\uXXXX` (about 24 KB for 4,000 chars).
- **Container bean:** `ServletServerContainerFactoryBean` broke the mock-web context test.
- **Close status:** once its limit trips, a `ConcurrentWebSocketSessionDecorator` rewrites any close status to 4500.
- **Spring Boot's `ObjectMapper`** writes ISO dates only because of a Boot default (it disables `WRITE_DATES_AS_TIMESTAMPS`).

**Tests.**

| Test | What it proves |
|---|---|
| `FrameCodecTest` | error vs close; "not found" and "not a member" frames are identical |
| `WsProtocolTest` | the escaped 24 KB frame is accepted |
| `WsSendAckTest` | ack after commit (mutation: an ack inside an outer transaction arrived for a rolled-back send); forged sender id ignored |
| `WsPushTest` | push targets, no duplicate pushes, no push on rollback; Tomcat's send timeout read back via reflection |
| `ConnectionRegistrySendTest` | slow consumer closed with 4003 |
| `WsPushQueueTest` | a full queue drops pushes but acks still arrive |
| `WsConcurrentSendTest` | 6 sockets × 20 concurrent sends: seq exactly 1..120, each message once per socket |
| `MessageBodyLimitTest` | 4,000-char limit |

**Fix.**
- **Frames and limits:** a versioned JSON envelope (ADR-0006); a 32 KiB limit set through Tomcat's init parameters.
- **Send and ack:** the handler isn't transactional and calls the `@Transactional` service, so the return *is* the commit.
- **Push:** a `MessageCreatedEvent` only for new rows, handled after commit on a bounded executor. Every send goes through a per-socket decorator; slow sockets are closed with 4003 via the raw session.

**Tradeoff.** Pushes are best effort: they can arrive out of order or be dropped. Recovery is the client's job (Phase 3).

**Follow-ups.**
- *Why error frames for some problems and closes for others?* If we can't even tell which request a frame belongs to, we can't reply usefully, so we close with a precise code. A bad field gets an error frame that carries the `clientMsgId`.
- *Why a decorator?* Concurrent writes to one WebSocket session are unsafe. The decorator serializes them and bounds time and buffer, so one slow client can't stall the others.
- *What if the push queue is full?* The push is dropped, logged and counted. History is the source of truth, so nothing is lost.

---

## Phase 2d-lite — heartbeats, delivered/read cursors, unread counts

**Problem.** Dead sockets lingered until their lifetime ended, there was no "delivered" or "read", and unread counts included your own messages.

**Bug found.** Unread was `lastSeq - lastReadSeq`, so A saw 5 unread where only 2 were from B (`UnreadCountTest` was red on the old code).

**Tests.**
- `HeartbeatMonitorTest` (fake clock) and `WsHeartbeatTest` (server-side ping and pong counters). Mutation "a pong doesn't refresh": failed 3 of 3.
- `CursorTest`: forward-only cursors; `xmin` unchanged by a stale cursor; 8 threads × 25 updates over 20 rounds; identical 404 for non-member, left participant and unknown conversation.
- `WsCursorTest`.

**Fix.**
- Protocol-level ping/pong; idle close with 4006 when the timeout (at least 2× the interval) passes.
- Cursor updates with the guard in the WHERE: `... AND last_read_seq < :seq`, read implies delivered.
- Unread counts messages from others after the read cursor (ADR-0007).

**Tradeoff.**
- Read receipts are visible to everyone.
- "Delivered" is client-reported.
- Cursor pushes fan out to all participants, so clients must debounce.

**Follow-ups.**
- *Why put the guard in the WHERE and not use `GREATEST` alone?* A stale update then matches no row and writes nothing (no new row version). Mutations: `GREATEST` without the guard is caught only by the `xmin` test; a read-then-write in Java broke the concurrent test (failed 5 of 5 runs in the latest check).
- *Why a WebSocket ping, not an app-level heartbeat frame?* Browsers answer pings automatically, so clients need no code.
- *Why not advance the sender's read cursor when they send?* It would silently mark other people's earlier unread messages as read.

---

## Phase 3 — reliable delivery and recovery

**Problem.** Make clients converge on PostgreSQL even though pushes are best effort.

**What we found.**
- **The first convergence test proved too little.** With anti-entropy disabled, it still passed (masked by later pushes and reconnects). We added a "tail" whose pushes are all dropped; anti-entropy is the only thing that can recover it.
- **The tail itself had a bug:** its own send could hit the injected commit failure, so it now resends with the same `clientMsgId`.
- **Masking is expected:** under faults, missing gap detection and fetch-before-subscribe are also masked by the other layers. Their own targeted tests carry them.

**Tests.**
- `HistoryForwardPagingTest`: `?after=`, `heads`, `lastSeq`, bad values, clamping, EXPLAIN.
- `BackoffTest` and `OutboxTest`.
- `ReferenceClientTest`: 17 tests, each guarantee in a positive and a negative (bug-flag) form.
- `ConvergenceTest`: 8 clients, seeded faults, 15 of 15 seed-runs converged.

**Fix.** A reference client in the test sources (ADR-0008):
- an outbox with stable ids and an ack timeout;
- subscribe first, then fetch;
- dedupe, gap fetch, jittered anti-entropy via `heads`;
- full-jitter reconnect;
- single-flight token refresh.

**Tradeoff.**
- Clients poll `heads`, and that cost is unmeasured (Phase 5).
- `heads` isn't paginated.
- The evidence covers one instance and a bounded workload, and seeds reproduce faults, not thread schedules.

**Follow-ups.**
- *Why subscribe before fetching?* Fetching first loses anything committed between the fetch and the subscribe. `ReferenceClientTest` shows it.
- *Why is anti-entropy needed if you have gap detection?* Gap detection fires only when a *later* push arrives, so a lost last message is invisible without it.
- *Why must a retry reuse the same `clientMsgId`?* The server's idempotency then makes the retry harmless. A new id per retry created a second row in the test.
