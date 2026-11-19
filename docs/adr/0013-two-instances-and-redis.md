# ADR-0013 — Two App Instances and Redis

**Status:** In progress (Phase 4; steps 0 and 1 of 5 written)  **Date:** 2026-11-19  **Phase:** 4

## Context
dox-chat.in runs one app instance. Every socket, push, theft close, rate limit and socket cap lives in that process, and every deploy disconnects everyone. Phase 4 runs two instances behind Caddy, so a user can be on either one and a deploy never takes the site down. The core principle doesn't change: **Postgres is the source of truth; Redis is only a delivery and coordination optimization.** The app must stay correct and healthy with Redis down.

## Decision 1: a cross-instance bus over Redis pub/sub, at most once (step 1)
- **Switch:** `realtime.bus=local|redis` (env `REALTIME_BUS`), default **`local`**. With `local` nothing crosses and nothing contacts Redis: the live single-instance server is unchanged by merging this.
- **Channel `chat:rt`, one envelope per delivery:** `{origin, kind, userIds, frame, excludeSessionId, closeCode, closeReason}` with three kinds:
  - `message` and `cursor`, from `MessagePushListener` after commit;
  - `close_user`, from `SessionRevocationListener.closeUser`, the single "close user" path from ADR-0012: a refresh-family revocation (4002), a guest expiry (4008), an operator removal (4002).
- **Flow:** the instance that committed delivers to its own sockets first (as before), then publishes. Every instance subscribes; an instance **ignores envelopes from itself** (its sockets were already served) and hands the rest to the same bounded executors as local work: pushes to the push executor (dropped and counted when full), closes to the realtime scheduler. Each socket gets each message exactly once in normal operation; the origin socket gets only its ack.
- **At most once, and why that's enough:** Redis pub/sub keeps nothing. A publish while Redis is down, or while a subscriber reconnects, is lost. That costs a *push*, never a message: the message was committed and acked before it was published, and every client already recovers gaps from Postgres (gap fetch, `heads` anti-entropy; ADR-0008). Pushes were always an optimization; the bus is the same optimization across instances.
- **A publish never blocks a push thread:** `publish()` only enqueues on the bus's own single-thread executor with a bounded queue (`realtime.bus.publish-queue`, 10,000); a full queue drops the envelope and counts it. The Redis call happens on that thread with a 200 ms command timeout, and a failure is caught and counted, never propagated to the sender. (Step 2 adds the circuit breaker in front of it and the paused-Redis latency test.)
- **Lettuce never buffers during an outage:** `disconnectedBehavior=REJECT_COMMANDS` (the default would queue every command in memory until Redis comes back) and a bounded `requestQueueSize` (1,000); auto-reconnect on.
- **Metrics (bounded tags):** `chat.rt.published{kind}`, `chat.rt.publish.failed{kind}`, `chat.rt.publish.dropped{kind}`, `chat.rt.received{kind}`.

## Decision 2: Redis never affects health; startup is safe with two instances (step 1)
- **`management.health.redis.enabled=false`:** the Redis starter would otherwise report the app DOWN whenever Redis is unreachable, and Caddy would stop sending it traffic. Redis is an optimization, so it never makes an instance unhealthy. (Step 2 tests "no Redis at all" and "Redis paused".)
- **Migrations:** two instances starting at once against an empty database migrate exactly once (Flyway's lock); `ConcurrentStartupTest` starts two contexts at the same moment on a fresh database and checks every version is applied once.
- **Seeding:** the bot and the demo accounts are created under a Postgres transaction advisory lock (`pg_advisory_xact_lock`). Without it, two instances both find no `dox-bot` and both insert it, and one fails to start on the duplicate key. A deterministic two-thread race test (25 rounds) shows exactly one account and no failure.

## Decision 3: the admin removal closes live sockets when Redis is up (step 1)
The runbook's `--remove-account` runs as a separate process (ADR-0012 Decision 9). With `REALTIME_BUS=redis` in its environment, its removal publishes `close_user`, and the runner flushes the bus before it exits, so the live instances close the account's sockets at once. Without Redis (or with the local bus), the old residual window applies: sockets already open until their lifetime ends (≤ 15 min), carrying nothing.

## Consequences
- **One new dependency:** `spring-boot-starter-data-redis` (Lettuce).
- **Tests:** `CrossInstanceDeliveryTest` (A→B and B→A pushes exactly once, a recipient on the origin instance gets no duplicate, cursor frames cross, `close_user` closes a socket on the other instance with 4002 (theft, operator removal) and 4008 (guest expiry)), `LocalBusTwoInstanceTest` (nothing crosses with the default bus; the message is stored and recoverable), `ConcurrentStartupTest`, and the runner's flush-before-exit in `RemoveAccountRunnerTest`. `TwoInstanceSupport` runs a second Spring context in the same JVM on the same Postgres, with a singleton Redis on a fixed port and one Micrometer registry per context.
