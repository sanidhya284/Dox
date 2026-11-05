# Benchmarks (Phase 5-lite)

**Status: template. No results yet.** Step 4 fills the result tables. Decisions are in [ADR-0009](adr/0009-observability-and-measurement.md).

> Results describe **this machine and this workload only. They are not capacity claims.** The load generator runs on the same laptop as the server, and on macOS Postgres runs inside Docker Desktop's Linux VM.

## 1. How to run

1. **Stop everything that competes for CPU.** That includes other projects' containers and the dev database; the harness records whatever is still running. Then start the dedicated benchmark database. It's never the dev one: own compose project `chatbench`, container `chat-bench-pg`, port 55432, own volume.
   ```bash
   deploy/bench/bench-db.sh reset      # empty bench DB (wipes only chat_bench_data)
   ```
2. **Build the app and start it with the `bench` profile.** The profile:
   - logs at INFO;
   - sets dev-only secrets and the metrics password;
   - sets `ws.session.max-lifetime` to PT2H, longer than any run;
   - exposes `/actuator/env` to the metrics user only.

   Run it as a jar, so it doesn't share a Gradle build with the harness.
   ```bash
   cd backend && ./gradlew bootJar
   SPRING_PROFILES_ACTIVE=bench java -Xms1g -Xmx1g -XX:+UseG1GC -jar build/libs/chatplatform-backend-0.1.0-SNAPSHOT.jar
   # Hikari pool for the 10 -> 30 re-run: BENCH_HIKARI_POOL=30
   ```
3. **Run one scenario at one rate** (one invocation = one run; one summary line; result JSON in `bench-results/`, git-ignored):
   ```bash
   ./gradlew loadTest -Pload.args="scenario=A rate=50 warmup=PT30S window=PT60S drain=PT15S label=run1"
   ```
   Other keys:
   - `groupSize` (A, B; default 10);
   - `groups` (B; default 10);
   - `fanUsers` and `socketsPerUser` (C; defaults 20 and 10);
   - `lagMs` (default 10);
   - `out`, `base`, `jdbc`.

   Exit code 3 means an invariant was violated, which is a **bug**, not a result.

## 2. Scenarios

| | Shape | Question |
|---|---|---|
| **A: hot conversation** | G users (default 10), **one** group, every user sends, round-robin, at the total open-loop rate | Where does send→ack stop being flat? The hypothesis is the conversation row lock (`SELECT ... FOR UPDATE` → `last_seq`), held until commit. |
| **B: control** | The **same** G users, sockets, rate and pushes per message (G−1), but K groups (default 10), each containing all G users; sends rotate over the groups | Only the number of hot rows differs from A. If B scales where A doesn't, the limit is the row, not the server in general. |
| **C: fan-out** | 1 sender; U users (default 20) × S sockets each (default 10) = 200 receive-only sockets in one group | The push path: 2-thread executor, queue, per-socket decorator. Measures send→receive spread, dropped pushes and 4003 closes. |

- **Scenario C uses fewer users with several sockets each.** One login per user (its registration) serves all of that user's sockets, each with its own ticket. That keeps BCrypt(12) logins out of the picture and matches real multi-tab use. Receivers are push-only sockets, not `ReferenceClient`s, because each `ReferenceClient` would log in again and poll `heads` every 30 s, which is extra load that isn't the push path.
- **Setup stays outside the measured window:** registrations (BCrypt), groups and socket connects; `setupSeconds` is recorded.
- **Senders are `ReferenceClient`s** (ADR-0008) with production defaults: 5 s ack timeout, 30 s anti-entropy. They use the real ticket flow, the stable-id outbox and the ack handling.

## 3. Method

- **Open loop.** Send *i* is intended at `start + i / rate`, whatever the server does. Latency is measured **from the intended time**:
  - send→ack, on the sending client;
  - send→receive, on every recipient. All clients share one JVM and one monotonic clock.

  Queueing delay is therefore inside the numbers (no coordinated omission). HdrHistogram's expected-interval correction is **not** applied on top.
- **Phases:** setup, then warm-up (default 30 s, discarded), then a **60 s window**, then drain (up to 15 s).
- **Invariants** are checked after closing every client, so the acked set is frozen, and after the DB row count is stable:
  - every acked clientMsgId is persisted;
  - no clientMsgId is stored twice;
  - rows without an ack are at most the sends still unacked at close (equality when drained);
  - per conversation, rows = max(seq) = `last_seq` and min(seq) = 1.
- **Repeats:** at least 3 runs per point, reported as median plus min and max. A point with fewer than 3 VALID or SATURATED runs isn't reported.
- **Percentiles:** p is reported only when at least 10 samples lie above it, n ≥ ceil(10 / (1 − p)), so p50 needs 20 samples, p95 200 and p99 1000. Otherwise `n/a` is shown with the max. This is a heuristic.
- **Sampled once a second in the window:**
  - Hikari active and pending;
  - process and system CPU;
  - push-executor queue and sockets (all from `/actuator/prometheus`);
  - `pg_stat_activity` grouped by state, `wait_event_type` and `wait_event` on the bench DB. Row-lock waits appear as `Lock:transactionid` and `Lock:tuple`.

  Server counter deltas over the window are approximate at the edges: the snapshots are taken at the boundary instants, not per message.
- **The row lock is a hypothesis.** It's accepted only if A saturates where B doesn't, the wait events are dominated by row locks, and a re-run of A with the Hikari pool at 30 (instead of 10) does or doesn't move the knee. Moving it would mean connections held while waiting are part of the limit.

### Run outcomes ([`RunClassifier`](../backend/src/test/java/com/chatplatform/load/RunClassifier.java), unit-tested)

| Outcome | Rule (heuristics) | Reported? |
|---|---|---|
| **INVARIANT_VIOLATION** | Any invariant above fails | No: it's a bug. Exit 3; stop and fix. |
| **SATURATED** | The server hit a limit: Hikari pending > 0 in ≥ 3 samples, ack timeouts or resends, sends unacked after drain, or ack p50 in the window's last third > 2× the first third **and** > 50 ms | Yes: it shows the knee |
| **INVALID** | No server limit, but the harness fell behind: p99 schedule lag > threshold, achieved rate < 95% of target, or harness GC > 5% of the window | No; fix the harness or the machine |
| **VALID** | None of the above | Yes |

When server and harness signals appear together, the run is SATURATED, with the harness signals listed as "also:". An overloaded server stops reading its sockets, so the client's writes block and schedule lag follows; that lag is back-pressure, not a harness fault.

### Schedule-lag threshold (calibrated; a heuristic)
- **Schedule lag** = time the send frame was written − intended time. **Pacer lag** (intended → handed to the client) is reported separately.
- **Idle calibration:** scenario A at 20/s, a 60 s window, 1200 samples; an idle server shows no Hikari pending. Schedule lag was p50 0.51 ms, p99 1.14 ms, max 1.41 ms, with pacer lag p99 0.51 ms. The threshold is set at **10 ms, about 9× the idle p99**.

### Harness faults found by the smoke runs (kept as lessons)
- **One long `parkNanos` per send oversleeps on macOS (timer coalescing).** At 5/s the idle schedule lag was 7.4 ms p50, so the classifier correctly marked that run INVALID. Parking in ≤ 1 ms slices fixed it.
- **The first invariant check compared the database with a still-growing acked set.** Under overload, acks landed between the query and the comparison, and the run was reported as an INVARIANT_VIOLATION. In that run, a second query taken a moment later already counted more rows than the first one, which is consistent with the race. The check now freezes the clients first. The re-run of the same overload, with the frozen check, had 10,000 sends and 10,000 rows, seq 1..10000, and every acked id persisted.

## 4. Environment (captured automatically into every result file)
- **Machine:**
  - CPU model and logical CPUs, RAM, macOS version;
  - power source and battery (`pmset -g batt`);
  - thermal state (`pmset -g therm`; a CPU speed limit below 100 means throttled).
- **Docker:** VM OS, CPUs and memory; the bench container's limits; **all other running containers**.
- **Postgres:** `server_version`, `shared_buffers`, `max_connections`, `synchronous_commit`, `fsync`, `wal_sync_method`, `work_mem`.
- **JVMs and code:**
  - the harness JVM and flags;
  - the app's command line (JVM flags);
  - the git commit and the number of dirty files.
- **App effective config,** from `/actuator/env`: `spring.datasource.*` (pool size), `ws.*`, `logging.*`, `management.*`, active profiles. Secrets are masked.

### Known confounders
- **The generator and the server share the laptop's CPUs.** The harness records its own GC share and schedule lag.
- **Docker Desktop's Linux VM** sits between the app and Postgres, and the WAL fsync goes to the VM's disk. It's not representative of a Linux server.
- **Retry amplification beyond the knee.** The client's 5 s ack timeout resends unacked messages with the same clientMsgId. The server deduplicates them, but each resend still takes the row lock. Past saturation, latency is therefore inflated by the client's own retries, which is one reason SATURATED points are shown as the knee, not as capacity.
- **Clients keep polling `heads`** every 30 s (anti-entropy), a small background load in every scenario.

## 5. Results (step 4; empty)

Each cell is the median [min–max] of 3 runs, in ms from the intended send time. Outcome counts are listed per point.

### A: hot conversation (pool 10)
| Rate/s | Outcomes | Achieved/s | ack p50 | ack p99 | ack max | recv p99 | Hikari pending s | Top wait events |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

### A: hot conversation (pool 30)
| Rate/s | Outcomes | Achieved/s | ack p50 | ack p99 | ack max | recv p99 | Hikari pending s | Top wait events |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

### B: control
| Rate/s | Outcomes | Achieved/s | ack p50 | ack p99 | ack max | recv p99 | Hikari pending s | Top wait events |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

### C: fan-out (200 sockets)
| Rate/s | Outcomes | recv p50 | recv p99 | recv max | Pushes dropped | 4003 closes | Push queue max |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

## 6. Bottleneck analysis (step 4)
To be written from the evidence:
- the knee of A against B;
- wait events at the knee;
- whether the pool-30 re-run moves the knee;
- what would change the result.

## 7. Later scenarios (not in this phase)
- connection ramp;
- reconnect storm;
- `heads` polling cost at many conversations;
- deliberate slow consumers.
