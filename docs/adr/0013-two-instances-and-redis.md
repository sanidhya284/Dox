# ADR-0013 — Two App Instances and Redis

**Status:** In progress (Phase 4; steps 0 to 4 of 5 written)  **Date:** 2026-11-19  **Phase:** 4

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

## Decision 8: drain before shutdown; a readiness check only the load balancer sees (step 4)
- **`/internal/ready`:** 200 while the instance accepts traffic, 503 from the moment it starts shutting down (Spring Boot switches readiness to `REFUSING_TRAFFIC` when the context starts closing). Caddy's active health check uses it on the internal network; Caddy never routes it publicly (from outside it is just the UI).
- **The public `/actuator/health` doesn't change.** It still means "alive and the database works" and its body is still `{"status":"UP"}`. Two rejected alternatives, both found by the drills:
  - Spring's probe groups add `"groups":["liveness","readiness"]` to the public body, which made the live post-deploy check (and `smoke.sh`) fail: merging would have broken every deploy.
  - Putting readiness into `/actuator/health` made the public health answer 503 during every planned drain: 2 failed requests per rolling deploy, and an outside monitor would see it too.
- **Drain (`app.shutdown.drain`, env `APP_SHUTDOWN_DRAIN`; 0 by default, 6 s with two instances):** `ShutdownDrain` stops first (highest lifecycle phase) and keeps the server serving normally for the drain. Then the existing graceful shutdown (20 s) lets in-flight requests finish, and sockets close with `1001`.
- **Shutdown budget:** drain 6 s + graceful shutdown up to 20 s + margin = **`stop_grace_period: 35s`** on both app containers in the two-instance override (Docker's default of 10 s would SIGKILL mid-shutdown). Measured once on the laptop: `docker compose stop app2` took 6.6 s, exit code 143 (SIGTERM honoured, not 137), 0 failed probe requests.
- **How fast Caddy notices** (`health_interval 1s`, `health_fails 2`, `health_timeout 1s`): the second failed check comes at most about 2 s after the drain starts, inside the 6 s drain. Observed: the drain started at 44.046 s, 503s at 44.442 and 45.443, so out of rotation after 1.4 s.

## Decision 9: Caddy load balancing, rolling deploys, and Caddy as the single point (step 4)
- **Caddyfile:** `APP_LB=single` (the default) adds nothing: the adapted config is identical to before Phase 4 apart from the file name, so the merged web image proxies one instance exactly as today. `APP_LB=pair` adds `lb_policy least_conn`, `lb_try_duration 5s` / `lb_try_interval 250ms` (a request that can't reach one instance is retried on the other), and the active health check above. No sticky sessions: a socket stays where it was accepted; tickets, tokens and limits are shared.
- **`compose.phase4.yml`** (an override): `app2` (extends `app`), `redis` (no persistence, 64 MB, `noeviction`, no ports), the two-instance env (`REALTIME_BUS`, `RATELIMIT_STORE`, `APP_SHUTDOWN_DRAIN`), `stop_grace_period: 35s` on both apps, and for `web`: both upstreams, `APP_LB=pair`, and the Caddyfile mounted from the server's `caddy/` directory.
- **`deploy.sh`:** without `compose.phase4.yml` next to `compose.yml` the single-instance path runs unchanged (no line of it changed). With it: start `db` and `redis` (no-ops when running), then roll `app`, wait until it's healthy, then `app2`. An instance that doesn't become healthy (or keeps restarting) is rolled back on its own while the other keeps serving. Then `web`, then the post-deploy check; if that fails, both instances go back one at a time.
- **Caddy is the single point.** When only the Caddyfile changed, `deploy.sh` reloads it in place (`caddy reload`): 0 failed requests in the drill; open WebSockets are closed by the reload (`1001`) and reconnect. When the UI (the web image's `/srv`) changed, `web` is recreated: **a blip of about 1 s** (measured once: 8 of 423 probe requests refused or reset within 0.98 s). Switching between one and two instances also recreates `web` (6 to 2 failed requests in under a second, measured). This blip is the remaining downtime; removing it needs a second proxy in front (an external load balancer or DNS failover), out of scope.
- **"No downtime", not "no disconnects":** a rolling deploy closes each socket at most twice (once per instance), and the sync protocol makes that harmless: the probe's socket client reconnected and converged every time, missing nothing.
- **Switching:** `switch_instances.sh two|one` on the server runs the deployed tag in the other mode in an order that keeps an instance serving (runbook). The deploy workflow copies the override as `compose.phase4.yml.example` and the Caddyfile to `caddy/`; both are inert until the operator switches.

## Consequences
- **One new dependency:** `spring-boot-starter-data-redis` (Lettuce).
- **Step 2 tests:**
  - `SharedLimitsTest`: login, send, new DMs and failed handshakes are each N in total across A and B; the socket cap counts both instances (4007 on the other one) and a freed slot can be reused there; two messages to the bot on different instances get one reply.
  - `RedisOutageTest`:
    - **Redis paused:** health UP; a command timeout opens the breaker; while open, 20 sends make 0 Redis attempts and none waits 200 ms; login, ticket and a new socket work; 30 s at 20 msg/s keeps local push latency at its baseline with no rejected push and a bounded number of Redis attempts; the other instance gets no pushes but has everything in history; pushes resume after unpause.
    - **Full restart:** sends work throughout; pushes resume; the socket set is rebuilt by the sweep; limits start again from empty.
    - **No Redis at all:** an instance starts, is UP and fully usable.
  - `SocketCapCrashTest`: live sockets keep counting past the timeout; a crashed instance's entries expire within timeout + interval.
  - `SocketCapAtomicityTest`: handshakes for one user on both instances are held at the admission point by a test-only hook and released together. At 9 sockets, 2 simultaneous handshakes admit exactly 1; 20 simultaneous handshakes with cap 10 admit exactly 10 (20 repetitions each). A two-step check-then-add with the hook between the steps admits all of them, every time, so the single script is what makes the cap hold.
  - `RedisGuardTest` (timeout opens; open means no attempts; one probe under contention); `RedisBusFlushTest` and `RemoveAccountRunnerTest` (bounded flush, "close lost" message).
- **Step 4 tests and drills:**
  - `ShutdownDrainTest`: during a 3 s drain `/internal/ready` is 503, the public health stays 200 with its body unchanged, normal requests are served; a 6 s request in flight when the stop began finishes. Mutations caught: no wait; the drain running after the web server stopped; `/internal/ready` always 200.
  - Laptop drills with the real images, compose files and `deploy.sh`, each under `deploy/drill/zero_failure_probe.py` (GETs, authenticated sends with unique ids, a WebSocket client that reconnects and catches up). Rolling deploy: 407 requests, 0 failed, 88 sends acked, all 88 in Postgres, the socket client reconnected twice and converged with nothing missing. Broken image on two instances: the bad `app` rolled back after 2 restarts, `app2` untouched, 248 requests, 0 failed. Caddyfile-only change: reloaded in place, 0 failed. UI change: the blip above. Single-instance `deploy.sh` re-run unchanged: deployed twice, health body `{"status":"UP"}`.
  - CI: the e2e job checks the single instance without Redis (health exactly `{"status":"UP"}`, the real post-deploy check); a new `two-instances` job runs `deploy/drill/two_instance_check.sh` (first deploy onto two instances, then a rolling deploy under the probe, which must report 0 failed) and `smoke.sh` on two instances. Playwright stays in the single-instance job (its raised limits are per container).
- **Step 3 tests:**
  - `TwoInstanceConvergenceTest`: the Phase 3 convergence test (same seeds, faults and assertions) with clients alternating between A and B, server-side faults on A, 20% of A's bus publishes dropped, sessions closed on both instances, and in the tail phase every push and every publish dropped. 5 runs x 3 seeds: 15/15 converged. With `NO_ANTI_ENTROPY` clients, seed 11 fails, as on one instance.
  - `GuestCleanupTwoInstanceTest`: both instances run the cleanup at the same moment over 30 expired guests; the counts add up to exactly the expired guests (15 + 15 in the recorded run), each is anonymized once, and sockets on either instance close with 4008. Claiming without the row lock removes each guest twice and fails it.
  - Already covered in earlier steps: the bot throttle across instances (`SharedLimitsTest`), and `close_user` with 4002 and 4008 across instances (`CrossInstanceDeliveryTest`).
- **Step 1 tests:** `CrossInstanceDeliveryTest` (A→B and B→A pushes exactly once, a recipient on the origin instance gets no duplicate, cursor frames cross, `close_user` closes a socket on the other instance with 4002 (theft, operator removal) and 4008 (guest expiry)), `LocalBusTwoInstanceTest` (nothing crosses with the default bus; the message is stored and recoverable), `ConcurrentStartupTest`, and the runner's flush-before-exit in `RemoveAccountRunnerTest`. `TwoInstanceSupport` runs a second Spring context in the same JVM on the same Postgres, with a singleton Redis on a fixed port and one Micrometer registry per context.
