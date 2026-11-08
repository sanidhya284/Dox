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

---

## Phase 5-lite — metrics, load harness, one measured bottleneck

**Problem.** Measure one instance honestly and find its first real bottleneck, without capacity claims.

**Result (one laptop; results are for this machine and this workload, not capacity claims).**
- **One conversation (scenario A, 10 senders, 1 group) held through 200/s and was unstable at 300/s.** At 300/s it was SATURATED in 1 of 3 runs at Hikari pool 10, and in 3 of 3 at pool 30.
- **At 300/s, 90–93% of the active database connections were waiting on a row lock,** mostly `Lock:tuple`: queues of transactions on one row.
- **The p50 latency fell as the rate rose (unexplained),** from 11.9 ms at 25/s to 3.8 ms at 200/s.
- Details: [BENCHMARKS.md](BENCHMARKS.md).

**The control (A versus B).**
- **B has the same senders, sockets, total rate and pushes per message, but 10 groups.** Only the number of hot rows differs. B kept up at 300/s in 3 of 3 runs.
- **So the limit is the row, not the server in general:** CPU, the network and the push work are the same in both.
- **Pool 30 didn't help in these runs,** and its p99 was higher; the cause wasn't investigated. Concurrency is capped by the 10 sender sockets anyway (each socket's frames are handled one at a time), so a bigger pool can only remove connection waits, not add parallelism.
- **The mechanism:** each send holds the conversation row from `SELECT ... FOR UPDATE` until commit, so sends to one conversation are serial. How much of the hold is the commit flush is a hypothesis; an optional run with `synchronous_commit=off` on the bench DB would test it.

**Bugs we found in our own tooling** (each was caught because the harness checks itself).
- **A late pacer.** One long `parkNanos` per send oversleeps on macOS (timer coalescing), giving a 7.4 ms p50 schedule lag at 5/s. The classifier marked the run INVALID. **Fix:** park in ≤ 1 ms slices; idle lag p99 is now about 1 ms.
- **A racy invariant check.** It compared the database with a still-growing set of acked ids, so under overload acks landed between the query and the comparison, and it reported a false INVARIANT_VIOLATION. **Fix:** close the clients first (freeze the set), wait until the row count is stable, then check.
- **A mislabelled run.** A run with a 3.6 s p50 was labelled VALID: its backlog formed during warm-up, so the "p50 doubled within the window" rule never fired. A second run was labelled INVALID by judging the lag *max*, because 300 samples can't support a p99.
  - **Fix, test-first with mutations:** SATURATED when the server stores < 95% of the sent rate, or ack p99 > 1000 ms (derived from the data, between the worst run that kept up, 356 ms, and the best one that didn't, 5534 ms). Too few lag samples gives the note "lag unsupported".
  - **Relabel:** all summaries relabelled from their recorded fields, no re-run, with the original label kept as `labelAtRun`.

**Tradeoff.**
- **Gap-free per-conversation `seq` costs serial sends per conversation** (ADR-0009 Decision 5).
- **The measurement has known confounders:** the generator shares the laptop, Postgres runs in Docker Desktop's VM, and 3 runs per point.

**Follow-ups.**
- *So it handles 300 messages a second?* No. On one laptop, one conversation was reliable at 200/s and unstable at 300/s. Spread over 10 conversations, it kept up at 300/s, and I didn't push further, so I don't know that limit. A laptop with the generator on the same machine and Postgres in a VM is not a capacity measurement.
- *How would you make a hot conversation faster?* First measure how much of the lock hold is the commit flush. If it's most of it, batch sends per conversation (one transaction, N contiguous `seq`): that keeps gap detection intact, at the cost of batch latency and complexity. Allocating `seq` outside the transaction (a sequence) removes the wait but creates holes and out-of-order commits, which breaks the client's gap detection. `synchronous_commit=off` can lose acked messages, so it's rejected.
- *Why didn't a bigger connection pool help?* The bottleneck is a lock on one row, and every extra connection just waits in the same lock queue. Also, only 10 senders existed, so there was no extra concurrency to serve. Pool 30 even had a higher p99; I didn't investigate why, and 3 runs at an unstable rate can't separate that from noise.

---

## Phase 6 — browser UI and a deployable stack (steps 1–4; the deploy pipeline is step 5)

**Problem.** Put the backend behind a real browser UI on the public internet, without opening holes the test client never needed.

**What we built and why.**
- **Rate limits in the app, not the proxy.**
  - **Why the app:** it knows the username and the user (per (IP, username) login, per-user tickets), and it can check before BCrypt.
  - **Memory is bounded:** expired windows are evicted, and new keys are refused when the map is full.
  - **Cost:** the limits are per instance, so Redis is needed in Phase 4.
- **Invite-only registration, checked before the conflict checks,** so nobody can learn which usernames exist without the code.
- **The refresh token moved to an HttpOnly, Secure, SameSite=Strict cookie** scoped to `/api/auth`, with an exact `Origin` check on refresh and logout. The access token stays in memory.
- **A TypeScript port of the Java reference client,** unit-tested with a fake server and fake timers.
- **One compose stack:** Caddy (TLS, UI, proxy), the app and Postgres, plus a smoke script and one Playwright test.

**What we found** (each caught by running something, not by reading code).
- **The HTTP client retried a 429.** TestRestTemplate's default Apache client honours `Retry-After` on 429, so the rate-limit tests hung instead of seeing the 429. The tests now use the JDK client.
- **A mutation that survived:** changing only the `@Value` default for the cookie's `Secure` flag changed nothing, because `application.yml` sets it. The config value is the real guarantee, so its mutation is the one that counts.
- **The healthcheck shell:** the app container's healthcheck used `/bin/sh`, which is dash, and dash has no `/dev/tcp`. The app was healthy but reported unhealthy.
- **Fail-closed order:** the bare image refused to start on `ws.allowed-origins is not set` before reaching the JWT check, so the smoke test checks both cases separately.
- **A test bug:** the e2e test's `getByLabel('Message')` also matched the "Messages" list (substring match).
- **The XFF check:** spoofed `X-Forwarded-For` doesn't dodge the per-IP limit, and the check was shown to fail when Caddy is told to trust every proxy.

**Tradeoffs.**
- **Cookie vs memory:** the cookie survives reloads and keeps the long-lived token away from JavaScript, but it adds CSRF (handled by SameSite plus Origin) and a multi-tab problem (handled by a Web Lock).
- **Strict replay detection:** a lost refresh response logs the user out.
- **Single instance:** deploys disconnect every socket for the restart time; no zero-downtime claim.

**Follow-ups.**
- *Why not keep the refresh token in localStorage?* Any XSS could read it and use it for a week from anywhere. With an HttpOnly cookie, XSS can act only while the page is open and can't take the long-lived credential away.
- *How do you stop CSRF if the cookie is sent automatically?*
  - SameSite=Strict, so it's not sent on cross-site requests;
  - an exact `Origin` check on the two cookie endpoints;
  - same-origin serving, so there's no CORS to misconfigure.
- *Why rate-limit in the app instead of nginx or Caddy?* The app knows the account being attacked, so it can key on (IP, username) and check before the expensive BCrypt. The price is per-instance memory, which moves to Redis when there are two instances.
- *What happens to open chats during a deploy?* The sockets close with `1001`. Clients reconnect with jittered backoff, fetch what they missed, and resend unacked messages with the same id, so there are no losses and no duplicates, just a visible reconnect.
