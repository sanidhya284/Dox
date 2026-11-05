# ADR-0009 — Observability and Honest Measurement (single instance)

**Status:** Accepted  **Date:** 2026-11-05  **Phase:** 5-lite

## Context
Phases 1–3 established correctness (ADRs 0002–0008), but nothing had been measured. Several docs say "unmeasured": push cost, `heads` polling, and whether the conversation row lock limits a hot conversation. Phase 5-lite adds metrics, one repeatable load harness, and one measured bottleneck, on **one instance**. Grafana, OpenTelemetry and Redis are explicitly out of scope.

## Decision 1: Micrometer metrics with low cardinality
- **Application meters:**
  - `chat.ws.sessions` (gauge);
  - `chat.messages.sent` (counter, `channel`, `outcome`);
  - `chat.ws.send.ack` (timer: server side, frame received → ack written);
  - `chat.push.dropped` (counter, `reason`);
  - `chat.ws.closes` (counter, `code` from a fixed set: 1000, 1001, 1007, 1009, 4001, 4002, 4003, 4005, 4006, else `other`);
  - `chat.ws.tickets` (counter, `outcome`);
  - `chat.cursor.updates` (counter, `outcome`);
  - `chat.heads.requests` (counter).
- **Free with the registry:** JVM, HikariCP, Tomcat, and push-executor metrics.
- **The rule: tags are bounded enums only, never user, conversation or session ids.** Per-id tags would create one time series per user or conversation, so memory and scrape size would grow without bound. A test enforces the allowed tag keys.
- **Consequence:** there's no per-conversation lock-wait metric. Lock contention is measured from the client side and from `pg_stat_activity` sampling during load runs (Decision 4).

## Decision 2: metrics are never public
- `/actuator/prometheus` and `/actuator/metrics` stay on the main port, behind a separate security chain (ordered before the JWT chain), with **HTTP Basic for one operator user, `metrics`**, whose password comes from `METRICS_PASSWORD`.
- The user exists only in that chain's own authentication manager, so the credentials don't work on the API (which accepts JWT only).
- **Fails closed:** a missing password, or one shorter than 16 characters, means no metrics user exists and every scrape gets 401.
- `/actuator/health` is unchanged: public, status only.
- **Rejected:** a separate management port on `127.0.0.1`. It would move `/actuator/health` off the main port and change existing behaviour.
- **Basic sends the password with every request:** production needs TLS in front (Phase 9).

## Decision 3: a Java load harness reusing the reference client (not k6)
- **Why:** the harness reuses `ReferenceClient` (ADR-0008), so the real ticket flow, the stable-id outbox and the ack semantics are exercised as clients really behave.
  - **Send→ack** is correlated by `clientMsgId` on the client that sent.
  - **Send→receive** is exact, because all clients share one JVM and one monotonic clock.
- **Nothing to install** beyond the JDK.
- **Rejected: k6.** It's an extra tool, and it would need the ticket flow and v1 frames re-implemented in JS. Cross-user send→receive needs shared state, which k6 virtual users don't have.
- **Where it runs:** test sources, a Gradle `loadTest` task, never in CI. It targets a separately started app (`bench` profile) and a **dedicated** benchmark Postgres container.

## Decision 4: measurement method (details in [BENCHMARKS.md](../BENCHMARKS.md))
- **Load model:** open-loop, constant arrival rate; latency measured from the **intended** send time, which avoids coordinated omission. HdrHistogram's expected-interval correction is **not** applied on top. Schedule lag (actual minus intended) is reported separately.
- **Runs:** warm-up discarded, then a fixed window; at least 3 runs per point, reported as median plus min and max. Percentiles are only reported when there are enough samples; otherwise the max is reported.
- **Environment capture** goes into each result file: hardware, OS, JVM flags, Docker limits, Postgres settings, Hikari pool, effective config, git commit, other running containers, power state.
- **Run classes:**
  - **VALID.**
  - **SATURATED:** the server limit was reached (latency growth, Hikari pending, outbox growth, ack timeouts). Reported, because it shows the knee.
  - **INVALID:** a harness fault (schedule lag, achieved rate below target with an idle server, harness GC share).

  **In every class the correctness invariants must hold** (DB rows = distinct acked ids; seq contiguous); a violation is a bug, not a result.
- **The row lock stays a hypothesis.** It's tested against `pg_stat_activity` wait events, Hikari active/pending samples, and a re-run with a larger pool.

## Consequences
- Results describe **this machine and this workload only; they are not capacity claims.**
- On macOS, Docker Desktop runs Postgres in a Linux VM, and the load generator shares the laptop with the server. Both are recorded as confounders.
- Numbers appear in the README only after they're measured, and they're linked to BENCHMARKS.md.
