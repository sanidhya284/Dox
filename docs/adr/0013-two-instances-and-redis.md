# ADR-0013 — Two App Instances and Redis

**Status:** In progress (Phase 4; steps 0 to 2 of 5 written)  **Date:** 2026-11-19  **Phase:** 4

## Context
dox-chat.in runs one app instance. Every socket, push, theft close, rate limit and socket cap lives in that process, and every deploy disconnects everyone. Phase 4 runs two instances behind Caddy, so a user can be on either one and a deploy never takes the site down. The core principle doesn't change: **Postgres is the source of truth; Redis is only a delivery and coordination optimization.** The app must stay correct and healthy with Redis down.

## Decision 1: a cross-instance bus over Redis pub/sub, at most once (step 1)
- **Switch:** `realtime.bus=local|redis` (env `REALTIME_BUS`), default **`local`**. With `local` nothing crosses and nothing contacts Redis: the live single-instance server is unchanged by merging this.
- **Channel `chat:rt`, one envelope per delivery:** `{origin, kind, userIds, frame, excludeSessionId, closeCode, closeReason}` with three kinds:
  - `message` and `cursor`, from `MessagePushListener` after commit;
  - `close_user`, from `SessionRevocationListener.closeUser`, the single "close user" path from ADR-0012: a refresh-family revocation (4002), a guest expiry (4008), an operator removal (4002).
- **Flow:** the instance that committed delivers to its own sockets first (as before), then publishes. Every instance subscribes; an instance **ignores envelopes from itself** (its sockets were already served) and hands the rest to the same bounded executors as local work: pushes to the push executor (dropped and counted when full), closes to the realtime scheduler. Each socket gets each message exactly once in normal operation; the origin socket gets only its ack.
- **At most once, and why that's enough:** Redis pub/sub keeps nothing. A publish while Redis is down, or while a subscriber reconnects, is lost. That costs a *push*, never a message: the message was committed and acked before it was published, and every client already recovers gaps from Postgres (gap fetch, `heads` anti-entropy; ADR-0008). Pushes were always an optimization; the bus is the same optimization across instances.
- **A publish never blocks a push thread:** `publish()` only enqueues on the bus's own single-thread executor with a bounded queue (`realtime.bus.publish-queue`, 10,000); a full queue drops the envelope and counts it. The Redis call happens on that thread with a 200 ms command timeout, and a failure is caught and counted, never propagated to the sender. Since step 2 the call goes through the circuit breaker (Decision 5) on the bus's own connection.
- **Lettuce never buffers during an outage:** `disconnectedBehavior=REJECT_COMMANDS` (the default would queue every command in memory until Redis comes back) and a bounded `requestQueueSize` (1,000); auto-reconnect on.
- **Metrics (bounded tags):** `chat.rt.published{kind}`, `chat.rt.publish.failed{kind}`, `chat.rt.publish.dropped{kind}`, `chat.rt.received{kind}`.

## Decision 2: Redis never affects health; startup is safe with two instances (step 1)
- **`management.health.redis.enabled=false`:** the Redis starter would otherwise report the app DOWN whenever Redis is unreachable, and Caddy would stop sending it traffic. Redis is an optimization, so it never makes an instance unhealthy (tested with no Redis at all and with Redis paused, Decision 5).
- **Migrations:** two instances starting at once against an empty database migrate exactly once (Flyway's lock); `ConcurrentStartupTest` starts two contexts at the same moment on a fresh database and checks every version is applied once.
- **Seeding:** the bot and the demo accounts are created under a Postgres transaction advisory lock (`pg_advisory_xact_lock`). Without it, two instances both find no `dox-bot` and both insert it, and one fails to start on the duplicate key. A deterministic two-thread race test (25 rounds) shows exactly one account and no failure.

## Decision 3: the admin removal closes live sockets when Redis is up (step 1)
The runbook's `--remove-account` runs as a separate process (ADR-0012 Decision 9). With `REALTIME_BUS=redis` in its environment, its removal publishes `close_user`, and the runner flushes the bus before it exits, so the live instances close the account's sockets at once. Without Redis (or with the local bus), the old residual window applies: sockets already open until their lifetime ends (≤ 15 min), carrying nothing.

## Decision 4: every limit is shared across instances, with a per-instance fallback (step 2)
- **Switch:** `ratelimit.store=local|redis` (env `RATELIMIT_STORE`), default **`local`** (in memory, per instance, as in ADR-0011).
- **With `redis`, one fixed window per limit and key in Redis:** a Lua script does `INCR`, sets `PEXPIRE` on the first hit and returns the count and the time left (`Retry-After`). Key `rl:<limit>:<key>`. Every limit goes through it: register, global sign-up, login (IP; IP + user), WS ticket, failed handshakes, search, guest per IP, send per user and per guest, new DMs per user and per guest, and the **bot reply throttle** (1 per 10 s per conversation, so two messages committed on different instances get one reply). `RateLimits` keeps its methods; only the store behind them changed.
- **Still outside the send transaction:** the send limit is checked where 6b put it, so a Redis round trip never runs while the conversation row lock is held (`SendLimitOutsideTransactionTest` unchanged).
- **When Redis fails** (error, timeout, breaker open) a call uses that instance's in-memory window. During an outage every shared limit becomes per instance, so two instances admit up to 2×:

| Limit | During an outage | Why acceptable |
|---|---|---|
| login per IP and per (IP, user), failed handshakes | up to 2× | Still limited. Failing closed would lock everyone out; failing open would drop brute-force protection. |
| register per IP, global sign-up (200/h) | up to 2× | Bounded and short-lived; the cap protects storage. |
| guest per IP | up to 2× | Same. The live-guest cap (1000) is a Postgres count, unaffected. |
| ticket, search, send, new DMs | up to 2× | Abuse limits, not correctness; refusing would break sending exactly when clients reconnect most. |
| socket cap (Decision 6) | up to 2× | A resource bound, not a security boundary. |
| bot throttle | may reply twice in 10 s | Cosmetic. |

- **After a full Redis restart** (no persistence) every window starts from zero: a documented reset, at most one extra window's worth per key.

## Decision 5: a circuit breaker in front of every Redis call; Redis never needed to start (step 2)
- **Opens on any failure, command timeouts included.** A *paused* Redis (or a stalled network) keeps the TCP connection open, so the client never sees a disconnect, only a timeout after the 200 ms command timeout. The breaker (`RedisGuard`, no new dependency) opens on the first exception of any kind, for 5 s.
- **While open, no Redis attempts and no waiting:** limits, the socket cap and publishes go straight to their fallback. Without it, every request would wait 200 ms during an outage.
- **Half-open: exactly one probe.** After 5 s one caller wins a compare-and-set and tries Redis; everyone else keeps the fallback until it settles. Success closes, failure reopens for another 5 s. During a 30 s pause that's a handful of commands, never one per request.
- **One breaker per instance,** shared by all Redis callers: one Redis, one signal.
- **Connections:** the limiter, the socket cap and the heartbeat refresh share Spring Boot's single Lettuce connection. **The bus publishes on its own connection**, which it creates and closes itself (not a bean, so Boot's auto-configuration is untouched), and the subscriber uses a dedicated subscription connection (required by pub/sub). Lettuce runs one connection's commands in order, so on a shared connection a stuck publish would delay the limiter calls queued behind it, and the other way round. Split, they can't. Each is bounded anyway by the 200 ms timeout and the breaker.
- **Startup doesn't need Redis:** Spring's listener container subscribes when the context starts and fails startup if Redis is unreachable (found by the "no Redis at all" test). It isn't auto-started; `BusSubscription` starts it on a background thread and retries every second. Until then this instance receives nothing from the bus (at most once; clients recover). Connect timeout 1 s.
- **The admin runner's flush is bounded** (3 s) and reports whether every publish reached Redis. If not (Redis down or paused, or `REALTIME_BUS=local`), it logs that the close was lost and the 15-minute window applies; the account is removed either way (exit 0).
- **Metrics:** `chat.redis.calls{op,result}` (real attempts), `chat.redis.fallback{op}`, `chat.redis.breaker.open`.

## Decision 6: the socket cap across instances, refreshed by the heartbeat (step 2)
- With `ratelimit.store=redis`, the per-user cap (ADR-0006, 10) counts sockets on every instance: one sorted set per user, `chat:sockets:<userId>`, member `<instance>:<session id>` (session ids are only unique per JVM), score = expiry = Redis time + `ws.heartbeat.timeout`.
- **Admission is one Lua script** (drop expired members, check the count, add), so concurrent connects on two instances can't both slip past the cap. The local cap still applies too.
- **Each heartbeat sweep re-adds this instance's open sessions** with a fresh expiry (plain `ZADD`, not `XX`, so the set rebuilds after a Redis restart). Sessions that closed while the sweep ran are removed again right after, so a refresh can't bring back a closed session. The key expires with its largest score (`PEXPIREAT`).
- **A crashed instance's sockets stop counting** within about timeout + interval (60 s + 25 s in production), because nothing refreshes them.
- **Fallback:** a Redis failure admits the socket and only the local cap applies (up to 2× across two instances).

## Decision 7: Redis memory policy `noeviction` (step 2)
Redis holds only small keys that expire on their own (limit windows, socket sets); pub/sub messages aren't stored. It runs without persistence and with `maxmemory 64mb` and **`maxmemory-policy noeviction`**. If it ever fills up, writes fail with an error, the breaker opens, and every caller uses its documented per-instance fallback, visibly (`chat.redis.fallback`, `chat.redis.breaker.open`). An evicting policy (`allkeys-lru`, `volatile-ttl`, ...) would instead drop live limit windows or socket sets **silently**, which resets someone's limit or under-counts their sockets with no signal. Failing loudly into a tested fallback is better than weakening limits quietly. (The tests' Redis uses the same settings; the production compose override comes in step 4.)

## Consequences
- **One new dependency:** `spring-boot-starter-data-redis` (Lettuce).
- **Step 2 tests:**
  - `SharedLimitsTest`: login, send, new DMs and failed handshakes are each N in total across A and B; the socket cap counts both instances (4007 on the other one) and a freed slot can be reused there; two messages to the bot on different instances get one reply.
  - `RedisOutageTest`:
    - **Redis paused:** health UP; a command timeout opens the breaker; while open, 20 sends make 0 Redis attempts and none waits 200 ms; login, ticket and a new socket work; 30 s at 20 msg/s keeps local push latency at its baseline with no rejected push and a bounded number of Redis attempts; the other instance gets no pushes but has everything in history; pushes resume after unpause.
    - **Full restart:** sends work throughout; pushes resume; the socket set is rebuilt by the sweep; limits start again from empty.
    - **No Redis at all:** an instance starts, is UP and fully usable.
  - `SocketCapCrashTest`: live sockets keep counting past the timeout; a crashed instance's entries expire within timeout + interval.
  - `RedisGuardTest` (timeout opens; open means no attempts; one probe under contention); `RedisBusFlushTest` and `RemoveAccountRunnerTest` (bounded flush, "close lost" message).
- **Step 1 tests:** `CrossInstanceDeliveryTest` (A→B and B→A pushes exactly once, a recipient on the origin instance gets no duplicate, cursor frames cross, `close_user` closes a socket on the other instance with 4002 (theft, operator removal) and 4008 (guest expiry)), `LocalBusTwoInstanceTest` (nothing crosses with the default bus; the message is stored and recoverable), `ConcurrentStartupTest`, and the runner's flush-before-exit in `RemoveAccountRunnerTest`. `TwoInstanceSupport` runs a second Spring context in the same JVM on the same Postgres, with a singleton Redis on a fixed port and one Micrometer registry per context.
