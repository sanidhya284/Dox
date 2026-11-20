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
   - `ackTimeout` (the client's ack timeout; default PT5S; knee runs use PT10M so client retries can't amplify the overload).

   The whole step-4 ladder runs unattended with `caffeinate -dims python3 deploy/bench/run_ladder.py` (`--dry-run` prints the plan and a time estimate). Each run first waits for a quiet server (no Hikari activity, no active query), so one overloaded run's backlog can't leak into the next.
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
| **SATURATED** | The server hit a limit: Hikari pending > 0 in ≥ 3 samples, ack timeouts or resends, sends unacked after drain, ack p50 in the window's last third > 2× the first third **and** > 50 ms, *(added after step 4)* the server storing < 95% of the sent rate, or ack p99 > 1000 ms | Yes: it shows the limit |
| **INVALID** | No server limit, but the harness fell behind: p99 schedule lag > threshold, achieved rate < 95% of target, or harness GC > 5% of the window | No; fix the harness or the machine |
| **VALID** | None of the above | Yes |

- **Too few lag samples** (fewer than the 1000 a p99 needs): lag isn't judged, and the run carries the note "lag unsupported (n=…)". *(Added after step 4; before that, the classifier judged the max instead.)*
- **The 1000 ms ack-p99 ceiling** is derived from the step-4 data and is a heuristic. The highest ack p99 of a run that kept up was 355.6 ms ([A-pool10, 300/s, pass 2](benchmarks/20261105-200631-A-r300-A-pool10-p2.json)); the lowest of a run that fell behind was 5533.7 ms ([A-pool30, 300/s, pass 3](benchmarks/20261105-212343-A-r300-A-pool30-p3.json)). 1000 ms sits between them, about 3× above the first and 5× below the second. Another machine needs its own derivation.

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

## 5. Predictions (written and committed BEFORE any step-4 measurement)

**What I already knew** when writing these:
- **From the smoke runs (harness checks, not results):** A at 400/s saturated hard, with Hikari pending and mostly `Lock:tuple` / `Lock:transactionid` wait events.
- **From the idle server:** the server-side ack time was about 10 ms.

So the A prediction isn't blind about "400/s is too much". Everything else is a guess from the design.

**Run plan:**
- **Ladder:** A and B at 25, 50, 100, 150, 200 and 300/s, at pool 10, and A again at pool 30. C at 2, 5, 10 and 20 messages/s across 200 sockets, i.e. up to 4000 pushes/s.
- **Repeats:** 3 interleaved passes per block.
- **Client ack timeout:** 10 min for every knee run, plus one A run with the default 5 s.

1. **A (hot conversation, pool 10):**
   - **Knee:** the first rate whose runs are mostly SATURATED will be **150 or 200/s**; I'd be surprised outside 100–300.
   - **Below the knee:** ack p50 stays roughly flat (10–25 ms).
   - **Why:** each send holds the conversation row lock from `SELECT ... FOR UPDATE` until commit, including the WAL fsync inside Docker's VM. Sends to one conversation are therefore serial, so the ceiling is about 1 / (lock hold time).
2. **B (control, same total rates):**
   - **No knee in the ladder:** every point VALID up to 300/s, ack p50 within about 2× of B's own 25/s value.
   - **Wait events:** mostly `ClientRead` and `IO:WALSync`, few `Lock:*`.
   - **Hikari pending:** essentially 0.
   - **If B saturates at the same rate as A**, the row-lock hypothesis is wrong: the limit would be the server or the database in general.
3. **A at pool 30:**
   - **The knee doesn't move by more than one ladder step.** Extra connections only queue on the same row lock.
   - **Hikari pending drops,** but `Lock:tuple` / `Lock:transactionid` waits rise by about the same amount, and latency at and above the knee is no better (possibly worse).
4. **The 5 s ack-timeout run**, at A's first saturated rate: many resends and server-side duplicates, and a higher ack p50 than the 10-min-timeout runs at the same rate. Retries add load exactly when the row is the bottleneck.
5. **C (fan-out, 200 sockets):**
   - **Outcome:** all runs VALID up to 20 messages/s, with no dropped pushes and no 4003 closes.
   - **Latency:** send→receive p99 grows with the rate, since each message is 200 sequential socket writes on a 2-thread push executor, but stays below 250 ms at 20/s.
   - **Least confident** about C at 20/s: the two push threads may queue.

## 6. Results (step 4, measured 2026-11-05)

> **These results predate Phase 6** (auth changes: the refresh token moved to a cookie, rate limits, a socket cap of 10 per user, invite-only registration). The harness was adapted to them, with open registration and high limits in the `bench` profile, but the published numbers were **not** re-measured.

**Results are for this machine and this workload; they are not capacity claims.**

**Setup** (from the result files):
- **Machine:** Intel i7-1068NG7 (8 logical CPUs), 16 GB RAM, macOS 26.3, on AC power. No thermal or performance warning was recorded.
- **Database:** Docker Desktop VM with 8 CPUs and 7.75 GiB. The only running container was `chat-bench-pg`, with no limits.
- **Postgres 16.15:** `shared_buffers` 128MB, `synchronous_commit` on, `fsync` on, `fdatasync`.
- **App and code:** the app JVM ran with `-Xms1g -Xmx1g -XX:+UseG1GC`, at commit `4aa799f` with 0 dirty files.
- **Timing:** warm-up 30 s, window 60 s, drain ≤ 15 s. All runs except the one comparison run used a client ack timeout of 10 min.

### Every run, with counts
- **Attempt 1: aborted, excluded.** Docker Desktop stopped during its second run, which then gave no result. Its one finished run (A-pool10 at 25/s, VALID) is kept apart in [`benchmarks/aborted-attempt-1/`](benchmarks/aborted-attempt-1/) and isn't used anywhere. The whole ladder was re-run from a fresh database.
- **Attempt 2 (the results below):** 67 points and **68 runs** in 2.04 h, with 0 invariant violations.
  - **Labels now:** 63 VALID, 5 SATURATED.
  - **Labels at run time:** 63 VALID, 4 SATURATED, 1 INVALID. That INVALID run was retried once, as the runner's rule required, which is why there are 68 runs for 67 points.
  - **Completeness:** every point has at least 3 usable runs.
- **The invariants held in every run,** including the saturated ones and the 5 s-timeout run.
- **Sampler errors:** one, an `InterruptedException` at sampler shutdown in A-pool30 300/s pass 3. All 60 server samples of that run were recorded.

### Relabelling (after step 4; nothing was re-run)
The step-4 data exposed two classifier gaps. Both rules were changed test-first in `RunClassifier`, and each new rule has a mutation shown to fail its test. Every stored summary was then relabelled from its **recorded** fields (`python3 deploy/bench/relabel.py`), and the run-time label is kept in each file as `labelAtRun`. Two outcomes changed:

| Run | At run time | Now | Why |
|---|---|---|---|
| [A-pool30, 300/s, pass 3](benchmarks/20261105-212343-A-r300-A-pool30-p3.json) | VALID | SATURATED | ack p99 5533.7 ms > 1000 ms (new rule) |
| [C, 5/s, pass 3](benchmarks/20261105-214158-C-r5-C-pool10-p3.json) | INVALID | VALID, note "lag unsupported (n=300 < 1000)" | lag isn't judged without enough samples for a p99 (new rule) |

1. **The A-pool30 run was backlogged.** Its ack p50 was 3.56 s and rose through the window (2634 → 3588 → 4596 ms), while the server stored 285.7/s of 300/s sent. The old 2× growth rule missed it, because the backlog had formed during warm-up and the growth within the window was only 1.74×. The new stored-rate rule (< 95%) doesn't fire either (95.2%); the ack-p99 ceiling does.
2. **The C run had too few samples to judge.** It had 300 lag samples. With p99 unsupported, the old code judged the **max** (25.1 ms) against the 10 ms threshold. Its retry ([file](benchmarks/20261105-214346-C-r5-C-pool10-p3-retry1.json)) stays in the data, so that point now has 4 runs, and all 4 are used (no cherry-picking).

The other SATURATED runs gained the new reasons alongside their old ones; none changed outcome.

### Reading the tables
- **Latency:** ms from the intended send time. Each cell is the median [min–max] over the point's runs, which is 3 runs except C at 5/s, which has 4.
- **n acks/run:** sends acked in the window per run. Each p99 rests on at least 1000 samples, except where `n/a` is shown.
- **Server stored/s:** `chat.messages.sent{outcome=created}` over the window ÷ 60. At or near the sent rate, the server kept up; below it, a backlog grew.
- **Hikari pending s:** one-second samples with pending > 0, per run.
- **Lock share:** active `pg_stat_activity` samples, summed over the runs, that waited on `Lock:tuple` or `Lock:transactionid`.
- **Regenerating:** these tables come from the summary files: `python3 deploy/bench/tables.py`.

<!-- tables:start (deploy/bench/tables.py) -->
### A: hot conversation (10 senders, 1 group), Hikari pool 10

| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s | Hikari pending s | Lock share | Runs |
|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 3 VALID | 1500 | 11.9 [10.9–12.1] | 16.4 [15.6–16.7] | 26.5 [23.6–27.8] | 17.9 [17.1–18.1] | 24.9 [24.9–24.9] | 0/0/0 | 0/13 (0%) | [p1](benchmarks/20261105-194708-A-r25-A-pool10-p1.json) [p2](benchmarks/20261105-195742-A-r25-A-pool10-p2.json) [p3](benchmarks/20261105-200816-A-r25-A-pool10-p3.json) |
| 50 | 3 VALID | 3000 | 8.1 [7.3–8.1] | 13.6 [13.2–13.7] | 19.4 [19.0–29.1] | 14.9 [14.7–15.0] | 49.9 [49.9–49.9] | 0/0/0 | 0/10 (0%) | [p1](benchmarks/20261105-194853-A-r50-A-pool10-p1.json) [p2](benchmarks/20261105-195928-A-r50-A-pool10-p2.json) [p3](benchmarks/20261105-201002-A-r50-A-pool10-p3.json) |
| 100 | 3 VALID | 6000 | 5.4 [5.0–5.5] | 8.4 [8.1–10.3] | 32.8 [15.3–184.7] | 9.6 [9.3–11.5] | 99.8 [99.8–99.8] | 0/0/0 | 7/26 (27%) | [p1](benchmarks/20261105-195039-A-r100-A-pool10-p1.json) [p2](benchmarks/20261105-200113-A-r100-A-pool10-p2.json) [p3](benchmarks/20261105-201148-A-r100-A-pool10-p3.json) |
| 150 | 3 VALID | 9000 | 4.2 [4.2–4.3] | 8.1 [8.1–8.7] | 38.0 [23.6–41.7] | 9.1 [9.0–10.0] | 149.8 [149.8–149.8] | 0/0/0 | 1/35 (3%) | [p1](benchmarks/20261105-195225-A-r150-A-pool10-p1.json) [p2](benchmarks/20261105-200259-A-r150-A-pool10-p2.json) [p3](benchmarks/20261105-201333-A-r150-A-pool10-p3.json) |
| 200 | 3 VALID | 12000 | 3.8 [3.7–3.8] | 16.5 [11.2–31.3] | 90.7 [40.8–158.2] | 17.9 [12.3–31.8] | 199.7 [199.7–199.7] | 0/0/0 | 9/69 (13%) | [p1](benchmarks/20261105-195410-A-r200-A-pool10-p1.json) [p2](benchmarks/20261105-200445-A-r200-A-pool10-p2.json) [p3](benchmarks/20261105-201519-A-r200-A-pool10-p3.json) |
| 300 | 2 VALID, 1 SATURATED | 18000 | 4.9 [4.5–3536.9] | 355.6 [266.5–11771.9] | 567.3 [487.2–12419.1] | 356.4 [267.5–11771.9] | 299.6 [269.1–299.8] | 2/1/10 | 844/940 (90%) | [p1](benchmarks/20261105-195556-A-r300-A-pool10-p1.json) [p2](benchmarks/20261105-200631-A-r300-A-pool10-p2.json) [p3](benchmarks/20261105-201712-A-r300-A-pool10-p3.json) |

### A: hot conversation, Hikari pool 30

| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s | Hikari pending s | Lock share | Runs |
|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 3 VALID | 1500 | 10.7 [10.6–11.3] | 16.3 [15.8–16.5] | 21.7 [20.3–40.5] | 17.7 [17.6–18.0] | 24.9 [24.9–24.9] | 0/0/0 | 0/14 (0%) | [p1](benchmarks/20261105-205316-A-r25-A-pool30-p1.json) [p2](benchmarks/20261105-210401-A-r25-A-pool30-p2.json) [p3](benchmarks/20261105-211449-A-r25-A-pool30-p3.json) |
| 50 | 3 VALID | 3000 | 7.5 [7.5–7.8] | 13.5 [13.5–13.7] | 60.8 [26.9–92.2] | 14.9 [14.8–15.1] | 49.9 [49.9–49.9] | 0/0/0 | 1/9 (11%) | [p1](benchmarks/20261105-205501-A-r50-A-pool30-p1.json) [p2](benchmarks/20261105-210546-A-r50-A-pool30-p2.json) [p3](benchmarks/20261105-211635-A-r50-A-pool30-p3.json) |
| 100 | 3 VALID | 6000 | 5.1 [5.1–5.2] | 8.8 [7.9–9.1] | 33.8 [18.9–70.7] | 9.9 [9.0–10.4] | 99.8 [99.8–99.9] | 0/0/0 | 0/28 (0%) | [p1](benchmarks/20261105-205647-A-r100-A-pool30-p1.json) [p2](benchmarks/20261105-210732-A-r100-A-pool30-p2.json) [p3](benchmarks/20261105-211821-A-r100-A-pool30-p3.json) |
| 150 | 3 VALID | 9000 | 4.3 [4.3–4.5] | 9.7 [7.8–10.3] | 37.1 [16.4–39.7] | 10.9 [8.9–11.5] | 149.8 [149.8–149.8] | 0/0/0 | 2/35 (6%) | [p1](benchmarks/20261105-205833-A-r150-A-pool30-p1.json) [p2](benchmarks/20261105-210918-A-r150-A-pool30-p2.json) [p3](benchmarks/20261105-212006-A-r150-A-pool30-p3.json) |
| 200 | 3 VALID | 12000 | 4.1 [3.8–4.5] | 100.0 [46.1–307.5] | 203.7 [129.2–417.0] | 101.2 [46.9–308.7] | 199.7 [199.1–199.8] | 0/0/0 | 86/151 (57%) | [p1](benchmarks/20261105-210019-A-r200-A-pool30-p1.json) [p2](benchmarks/20261105-211104-A-r200-A-pool30-p2.json) [p3](benchmarks/20261105-212152-A-r200-A-pool30-p3.json) |
| 300 | 3 SATURATED (1 relabelled†) | 18000 | 5820.4 [3563.5–9617.4] | 9592.8 [5533.7–13631.5] | 9920.5 [5832.7–13991.9] | 9592.8 [5533.7–13631.5] | 276.1 [254.5–285.7] | 0/0/0 | 1555/1665 (93%) | [p1](benchmarks/20261105-210215-A-r300-A-pool30-p1.json) [p2](benchmarks/20261105-211303-A-r300-A-pool30-p2.json) [p3](benchmarks/20261105-212343-A-r300-A-pool30-p3.json) |

### B: control (same 10 senders, 10 groups), Hikari pool 10

| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s | Hikari pending s | Lock share | Runs |
|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 3 VALID | 1500 | 11.1 [10.7–11.8] | 16.2 [15.9–16.4] | 41.1 [20.8–82.0] | 17.9 [17.4–17.9] | 24.9 [24.9–24.9] | 0/0/0 | 0/8 (0%) | [p1](benchmarks/20261105-202116-B-r25-B-pool10-p1.json) [p2](benchmarks/20261105-203152-B-r25-B-pool10-p2.json) [p3](benchmarks/20261105-204229-B-r25-B-pool10-p3.json) |
| 50 | 3 VALID | 3000 | 8.1 [8.0–8.2] | 13.7 [13.6–13.9] | 36.3 [29.0–47.6] | 15.2 [14.9–15.3] | 49.9 [49.9–49.9] | 0/0/0 | 0/19 (0%) | [p1](benchmarks/20261105-202302-B-r50-B-pool10-p1.json) [p2](benchmarks/20261105-203338-B-r50-B-pool10-p2.json) [p3](benchmarks/20261105-204415-B-r50-B-pool10-p3.json) |
| 100 | 3 VALID | 6000 | 5.4 [5.3–5.4] | 8.9 [8.8–9.2] | 36.2 [31.9–47.8] | 10.5 [10.1–10.5] | 99.8 [99.8–99.9] | 0/0/0 | 0/28 (0%) | [p1](benchmarks/20261105-202448-B-r100-B-pool10-p1.json) [p2](benchmarks/20261105-203525-B-r100-B-pool10-p2.json) [p3](benchmarks/20261105-204601-B-r100-B-pool10-p3.json) |
| 150 | 3 VALID | 9000 | 4.5 [4.4–4.5] | 10.3 [9.6–10.3] | 42.9 [42.0–149.4] | 11.5 [11.0–11.7] | 149.8 [149.8–149.8] | 0/0/0 | 6/58 (10%) | [p1](benchmarks/20261105-202634-B-r150-B-pool10-p1.json) [p2](benchmarks/20261105-203711-B-r150-B-pool10-p2.json) [p3](benchmarks/20261105-204747-B-r150-B-pool10-p3.json) |
| 200 | 3 VALID | 12000 | 4.1 [4.0–4.1] | 14.7 [14.3–14.9] | 53.2 [47.0–63.5] | 16.2 [15.7–16.3] | 199.7 [199.7–199.7] | 0/0/0 | 3/63 (5%) | [p1](benchmarks/20261105-202820-B-r200-B-pool10-p1.json) [p2](benchmarks/20261105-203857-B-r200-B-pool10-p2.json) [p3](benchmarks/20261105-204933-B-r200-B-pool10-p3.json) |
| 300 | 3 VALID | 18000 | 4.9 [4.8–5.0] | 31.2 [28.5–32.1] | 76.5 [67.8–85.1] | 32.4 [30.1–33.4] | 299.6 [299.6–299.6] | 0/0/0 | 109/194 (56%) | [p1](benchmarks/20261105-203006-B-r300-B-pool10-p1.json) [p2](benchmarks/20261105-204043-B-r300-B-pool10-p2.json) [p3](benchmarks/20261105-205119-B-r300-B-pool10-p3.json) |

### C: fan-out (1 sender, 200 receive-only sockets, so 200 pushes per message)

| Rate/s | Outcomes | n acks/run | n receives/run | ack p50 | recv p50 | recv p99 | recv max | Pushes dropped | 4003 closes | Push queue max | Runs |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2 | 3 VALID | 120 | 24000 | 13.7 [13.2–14.6] | 17.3 [16.4–18.6] | 27.1 [24.6–31.7] | 38.4 [29.1–39.0] | 0 | 0 | 0 | [p1](benchmarks/20261105-212543-C-r2-C-pool10-p1.json) [p2](benchmarks/20261105-213257-C-r2-C-pool10-p2.json) [p3](benchmarks/20261105-214010-C-r2-C-pool10-p3.json) |
| 5 | 4 VALID (1 relabelled†) | 300 | 60000 | 13.4 [12.5–14.1] | 16.6 [15.8–17.6] | 21.3 [20.7–23.0] | 26.7 [25.1–40.5] | 0 | 0 | 0 | [p1](benchmarks/20261105-212732-C-r5-C-pool10-p1.json) [p2](benchmarks/20261105-213446-C-r5-C-pool10-p2.json) [p3](benchmarks/20261105-214158-C-r5-C-pool10-p3.json) [p3-retry1](benchmarks/20261105-214346-C-r5-C-pool10-p3-retry1.json) |
| 10 | 3 VALID | 600 | 120000 | 12.7 [12.3–12.8] | 15.8 [15.5–15.9] | 20.8 [20.6–21.1] | 25.2 [24.4–34.3] | 0 | 0 | 1 | [p1](benchmarks/20261105-212920-C-r10-C-pool10-p1.json) [p2](benchmarks/20261105-213634-C-r10-C-pool10-p2.json) [p3](benchmarks/20261105-214535-C-r10-C-pool10-p3.json) |
| 20 | 3 VALID | 1200 | 240000 | 12.2 [10.9–12.3] | 15.3 [13.8–15.4] | 21.7 [20.9–22.8] | 32.7 [25.2–54.0] | 0 | 0 | 1 | [p1](benchmarks/20261105-213109-C-r20-C-pool10-p1.json) [p2](benchmarks/20261105-213822-C-r20-C-pool10-p2.json) [p3](benchmarks/20261105-214723-C-r20-C-pool10-p3.json) |

† Outcome re-derived after the run by the current classifier from the recorded fields (see "Relabelling" above); the run-time label is kept in the file as `labelAtRun`.
<!-- tables:end -->

### The 5 s ack-timeout run (A, pool 10, 300/s, the production default; [file](benchmarks/20261105-201916-A-r300-A-ack5s-p1.json))
No A-pool10 rate was mostly SATURATED, so the runner used the top rate, 300/s. **Outcome: SATURATED.**

| | This run (5 s timeout) | Same point, 10 min timeout (3 runs) |
|---|---|---|
| Client resends | **17,733** | 0 |
| Server-side duplicates in the window | **2,980** (49.7/s) | 0 |
| Server stored/s | 266.6 | 299.6 [269.1–299.8] |
| Sends unacked after drain | 403 | 0 |
| Sends refused by the full client outbox | 379 | 0 |
| ack p50 / p99 / max | 178.1 / 44,433 / 45,974 ms | 4.9 / 355.6 / 567.3 ms (median run) |

- **Resends compete for the bottleneck.** The server processed about 316 sends/s in the window (266.6 created + 49.7 duplicates). So about 16% of the work spent on the hot row went to messages that were already stored.
- **Correctness held.** 252 rows were committed without an ack reaching the client; a real client resends them and gets the original ack back. Every acked id was persisted, and seq was contiguous 1..26470.
- **The p50 isn't comparable.** Latency only counts sends that got an ack, and this run shed load at the client (outbox full; unacked at close), so its p50 looks better than the backlogged 10-min run. The p99 and max show the effect.

## 7. Bottleneck analysis

**Conclusion (this machine, this workload):**
- **One conversation (A) held through 200/s and was unstable at 300/s.**
  - **At 200/s:** all 6 runs kept up (3 at pool 10, 3 at pool 30).
  - **At 300/s:** 1 of 3 runs was SATURATED at pool 10, and 3 of 3 at pool 30.
- **The same total load over 10 conversations (B)** kept up at 300/s in 3 of 3 runs.
- **The cause:** the evidence points to the **conversation row lock** (`SELECT ... FOR UPDATE`, held until commit), not to CPU, waiting for a pooled connection, or the push path.

### Evidence for
1. **A versus B: only the number of hot rows differs.**
   - **Up to 150/s:** they're indistinguishable (p50 and p99 within about 1 ms).
   - **At 300/s:** B kept up (299.6 msg/s stored) with p99 28.5–32.1 ms. The 4 SATURATED A runs stored 254.5–285.7 msg/s, with latency growing into seconds.
   - **Why it isn't CPU:** B does the same CPU, network and push work at the same rate. The app process averaged ≤ 4.7% of the machine's 8 logical CPUs in every run.
2. **Wait events.**
   - **At 300/s,** 90% (pool 10) and 93% (pool 30) of active-connection samples in A were waiting on a row lock, mostly `Lock:tuple`. In Postgres, the first waiter on a row waits on the holder's transaction id (`Lock:transactionid`), and further waiters queue on the tuple lock (`Lock:tuple`). A majority of `Lock:tuple` therefore means queues of several transactions on one row.
   - **At 150/s,** the same share was 3% (pool 10) and 6% (pool 30).
3. **Not connection waits.** At pool 30, Hikari pending was 0 in every A run, yet all three A runs at 300/s were SATURATED.
4. **B shows the same mechanism, at a tenth of the per-row rate.** At 300/s, 56% of B's active samples were lock waits too, mostly `Lock:transactionid`, i.e. single waiters. Each of B's 10 groups gets 30/s from 10 senders. The rows were contended, but the queues stayed short.
5. **A consistency check (derived, not measured directly).**
   - **The inputs:** in the backlogged A runs, about 10 transactions are in flight (one per sender socket; Hikari active max 10–11), and the server stored 254–286/s.
   - **The arithmetic:** Little's law gives 10 / 286 ≈ 35 ms to 10 / 254 ≈ 39 ms per send inside the server. The measured server-side ack timer mean in those runs was 35.0–39.3 ms.
   - **The implication:** about 3.5–3.9 ms per lock hold.
   - **What's a hypothesis:** how much of that hold is the commit's WAL flush. `IO:WALSync` appears in the samples, but its share of the hold wasn't measured. The optional overnight run (`synchronous_commit=off` on the bench DB only; HANDOFF P5-2) would test it.

### Caveats (what the evidence doesn't show)
- **Pool 30 didn't help in these runs, and its p99 was higher; the cause wasn't investigated.**
  - **The numbers:** at 300/s, 3 of 3 runs were SATURATED versus 1 of 3 at pool 10. At 200/s, the p99 was 100.0 [46.1–307.5] ms versus 16.5 [11.2–31.3] ms.
  - **Why it was a weak test:** concurrency is capped by the 10 sender sockets, since each socket's frames are handled one at a time, and Hikari active never exceeded 11. Pool 30 removed the pending waits but couldn't add concurrency.
  - **What it doesn't show:** what more concurrent senders would do.
  - **Run-to-run variation:** A at 300/s is unstable even at pool 10 (two runs stored 299.8 and 299.6 msg/s, one 269.1), so 3 runs per pool can't separate a pool effect from that variation.
- **Throughput fell once a backlog formed.** Backlogged runs stored 254–286/s, while the two pool-10 runs that kept up sustained 299.6–299.8/s. Why wasn't investigated.
- **A's ack p50 fell as the rate rose,** from 11.9 ms at 25/s to 3.8 ms at 200/s. B shows the same, and so does the server-side ack mean. **It's unexplained,** and it wasn't investigated.
- **B's ladder stopped at 300/s,** so B's own limit is unknown; it's only known to be above 300/s.

### What would change the conclusion
- **B at higher rates (for example 400–600/s):** if B became unstable at about the same total rate as A, the limit would be general (fsync, CPU or the VM), not the hot row.
- **A with more sender sockets at the same total rate (for example 30) and pool 30:** if A then kept up at 300/s, the limit would be per-socket serial handling, not the row.
- **A shorter lock hold:** as an experiment on the bench database only, `synchronous_commit=off`, or a faster disk. If that didn't move A's limit, the commit flush wouldn't be the main cost of the hold.
- **Linux with Postgres outside a VM** would likely move the numbers a lot. The mechanism (one row, serial commits) should stay.

### How the predictions held up (section 5, committed before measuring)
Section 5 is unchanged, and the labels below are the current ones.

| # | Prediction | Result |
|---|---|---|
| 1 | A's knee at 150 or 200/s (surprised outside 100–300); flat ack p50 of 10–25 ms below it | **Wrong on the rate.** A held through 200/s and was unstable at 300/s (pool 10: 1 of 3 SATURATED), so no ladder rate was *mostly* SATURATED at pool 10. **The p50 was also wrong:** 3.8–11.9 ms, and falling as the rate rose (unexplained). |
| 2 | B: no limit up to 300/s; p50 within 2× of its 25/s value; mostly `ClientRead` / `IO:WALSync`; pending 0 | **Held** for the limit, the p50 (4.9 vs 11.1 ms) and pending (0). **Wrong** on the wait events at 300/s: 56% were lock waits, because each B row still has 10 senders. |
| 3 | Pool 30: the limit moves at most one step; pending drops; lock waits rise; latency no better | **Held:** no improvement, pending fell to 0, lock share went from 90% to 93%. Pool 30 didn't help in these runs, and its p99 was higher; the cause wasn't investigated. The test was weaker than intended (concurrency capped at 10–11). |
| 4 | 5 s timeout: many resends and duplicates; higher p50 | **Held** for resends (17,733) and duplicates (2,980). **Not comparable** for p50 (survivor bias, see above); p99 and max were much higher. |
| 5 | C: all VALID to 20/s, no drops or 4003, recv p99 < 250 ms, p99 growing with rate; push threads may queue at 20/s | **Held:** 13 of 13 runs VALID after relabelling (12 plus 1 INVALID at run time), 0 drops, 0 `4003`, p99 ≤ 31.7 ms. **Wrong:** the p99 didn't grow with the rate (27.1 ms at 2/s, 21.7 ms at 20/s), and the push queue never held more than 1 task. |

## 7a. Phase 6b re-run: A at 200/s and B at 300/s (2026-11-11) — confounded, to be repeated

**Why:** Phase 6b added work to every send: a check that the sender's account isn't removed and a duplicate lookup (both before the transaction, each its own query), the send-limit check (in memory), and inside the transaction a DM recipient check (`requireRecipientAvailable`, one query, currently **after** the row lock is taken). The `bench` profile sets every per-user limit to 100000, so the limits themselves never refused anything.

**How:** the same harness and method as section 6 (`run_ladder.py` functions; warm-up 30 s, window 60 s, drain 15 s, `ackTimeout` PT10M, pool 10, 3 passes), only these two points, labelled `-6b`. Code: commit `0dfcbde` plus the uncommitted fix (send limit outside the transaction, removed-caller checks). Other containers were stopped (only `chat-bench-pg` ran, as recorded in each file).

| Point | Before (section 6) | After (Phase 6b) |
|---|---|---|
| A 200/s: outcomes | 3 VALID | 2 SATURATED, 1 VALID |
| A 200/s: ack p50 / p99 (median [min–max]) | 3.8 / 16.5 [11.2–31.3] ms | 1062.9 / 11370.5 [165.0–12263.4] ms |
| A 200/s: server stored/s, Hikari pending s, lock share | 199.7; 0/0/0; 13% | 196.1 [174.3–199.7]; 35/20/0; 90% |
| B 300/s: outcomes | 3 VALID | 3 SATURATED (all only by "Hikari pending > 0") |
| B 300/s: ack p50 / p99 | 4.9 / 31.2 [28.5–32.1] ms | 20.4 / 482.1 [102.1–517.4] ms |
| B 300/s: server stored/s, Hikari pending s, lock share | 299.6; 0/0/0; 56% | 299.5; 10/11/4; 74% |

Files: A [p1](benchmarks/20261111-115947-A-r200-A-pool10-6b-p1.json) [p2](benchmarks/20261111-120144-A-r200-A-pool10-6b-p2.json) [p3](benchmarks/20261111-120331-A-r200-A-pool10-6b-p3.json); B [p1](benchmarks/20261111-120531-B-r300-B-pool10-6b-p1.json) [p2](benchmarks/20261111-120720-B-r300-B-pool10-6b-p2.json) [p3](benchmarks/20261111-120909-B-r300-B-pool10-6b-p3.json).

**Not a clean comparison. Two confounders, both visible in the result files:**
1. **Power:** every Phase 6b run was on **battery** (51% and discharging at the start); every section-6 run was on **AC**. macOS can lower CPU performance on battery.
2. **Warm-up:** in section 6 each 200/s or 300/s run came after the lower rates of the same pass (a warm JVM and a used database); here each block started with its first rate on a freshly started app. The one VALID A run is the third pass.

**What it suggests (not shown):** a slower send path. The lock share rose (A 13% → 90%, B 56% → 74%) and Hikari pending appeared, which fits transactions holding the row lock longer (the recipient check runs under the lock) and more connections per send (two extra queries before the transaction). Neither has been isolated.

**Next:** repeat on AC power with the section-6 warm-up (the lower rates first); if the regression stays, move the recipient check before the row lock and merge the two pre-transaction queries into one, and measure again.

## 7b. Phase 6b re-run 2: AC power, with the section-6 warm-up (2026-11-11)

**How:** the same ladders as section 6 up to the compared points, on AC power (battery full, not charging), each pass climbing the rates on one running app: A at 25, 50, 100, 150, 200/s and B at 25 … 300/s, pool 10, 3 passes, `ackTimeout` PT10M. Labels `-6b2`. Code: `main` at `4a6a472` (Phase 6b merged). Only `chat-bench-pg` ran (recorded in each file). 33 points, 37 runs including the runner's automatic retries of INVALID runs.

| Point | Before (section 6) | Re-run 2 (Phase 6b) |
|---|---|---|
| B 300/s: outcomes | 3 VALID | 3 VALID |
| B 300/s: ack p50 / p99 | 4.9 / 31.2 [28.5–32.1] ms | 4.9 / 28.9 [28.4–32.9] ms |
| B 300/s: stored/s, Hikari pending s, lock share | 299.6; 0/0/0; 56% | 299.6; 2/0/0; 40% |
| B 200/s: ack p99 | 14.7 [14.3–14.9] ms | 13.1 [13.0–20.6] ms |
| A 200/s: outcomes | 3 VALID | 2 VALID, 1 SATURATED |
| A 200/s: ack p99 per pass | 16.5 [11.2–31.3] ms | p1 22.5, p2 5308.4 (SATURATED), p3 323.8 ms |

**Reading it:**
- **B (ten conversations): no regression.** Every B point matches section 6 within run-to-run variation, including 300/s. The section-7a result (3 of 3 SATURATED) came from the battery and the cold start, not from the Phase 6b send path.
- **A (one hot conversation): inconclusive, not a shown regression.** Pass 1 was clean (schedule lag p99 ≤ 0.84 ms at every rate) and its 200/s p99 of 22.5 ms is inside section 6's range. Passes 2 and 3 ran under outside contention: the harness's own schedule lag was 7–20 ms **even at 25/s** (threshold 10 ms; 3 runs were INVALID and retried), against below 1 ms in pass 1 and in every B run. The SATURATED A run (p2, 200/s) also had schedule lag p99 22.6 ms. A at 200/s sits right below the knee found in section 7 (it held at 200/s, was unstable at 300/s), so it is the point most sensitive to a busy laptop.
- **Not shown:** whether the Phase 6b send path costs a little at A 200/s. One clean pass can't separate a small cost from run-to-run variation.

**Decision:** no code change on this evidence; the candidate optimisation (the DM recipient check before the row lock, one pre-transaction query instead of two) stays a candidate. If A is re-measured, run the A block alone with nothing else in use.

Files: `docs/benchmarks/*-6b2-*.json`. Section 7a's battery-power runs stay above, marked confounded.

## 7c. Phase 4 step 0: the single-instance baseline (2026-11-19)

**Why:** Phase 4 (two instances + Redis, ADR-0013) will be compared against this. It also settles the open question from 7b (A at 200/s on the Phase 6b code).

**How:** the section-6 ladders, on AC power (battery full), each pass climbing the rates on one running app: A at 25, 50, 100, 150, 200/s and B at 25 … 300/s, pool 10, 3 passes, `ackTimeout` PT10M. Code: `main` at `4a6a472` (Phase 6b; single instance, no Redis). Before the first run the script waited until no other process used more than 15% CPU (it waited 60 s for macOS app verification to finish). Only `chat-bench-pg` ran. Labels `-p4base`.

**Clean:** 33 points, **33 runs, all VALID on the first attempt** (no retries); the harness's schedule-lag p99 was at most 1.08 ms in every run (threshold 10 ms); Hikari pending 0 in every run.

### A: hot conversation, single instance, pool 10 (Phase 4 step 0)

| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s | Hikari pending s | Lock share | Runs |
|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 3 VALID | 1500 | 16.1 [16.0–16.5] | 20.6 [20.1–20.7] | 25.9 [25.3–42.1] | 22.1 [21.5–22.1] | 24.9 [24.9–24.9] | 0/0/0 | 0/1 (0%) | [p1](benchmarks/20261119-134104-A-r25-A-pool10-p4base-p1.json) [p2](benchmarks/20261119-134954-A-r25-A-pool10-p4base-p2.json) [p3](benchmarks/20261119-135844-A-r25-A-pool10-p4base-p3.json) |
| 50 | 3 VALID | 3000 | 11.2 [10.2–11.2] | 16.2 [16.1–16.9] | 45.9 [44.5–78.2] | 17.5 [17.4–18.6] | 49.9 [49.9–49.9] | 0/0/0 | 0/8 (0%) | [p1](benchmarks/20261119-134250-A-r50-A-pool10-p4base-p1.json) [p2](benchmarks/20261119-135140-A-r50-A-pool10-p4base-p2.json) [p3](benchmarks/20261119-140029-A-r50-A-pool10-p4base-p3.json) |
| 100 | 3 VALID | 6000 | 6.6 [6.5–6.6] | 9.9 [9.9–10.1] | 96.7 [33.2–130.8] | 11.1 [11.0–11.2] | 99.8 [99.8–99.9] | 0/0/0 | 0/18 (0%) | [p1](benchmarks/20261119-134436-A-r100-A-pool10-p4base-p1.json) [p2](benchmarks/20261119-135326-A-r100-A-pool10-p4base-p2.json) [p3](benchmarks/20261119-140214-A-r100-A-pool10-p4base-p3.json) |
| 150 | 3 VALID | 9000 | 4.7 [4.6–4.8] | 9.8 [9.4–130.2] | 57.5 [31.0–376.8] | 10.8 [10.5–134.1] | 149.8 [149.8–149.8] | 0/0/0 | 15/51 (29%) | [p1](benchmarks/20261119-134622-A-r150-A-pool10-p4base-p1.json) [p2](benchmarks/20261119-135512-A-r150-A-pool10-p4base-p2.json) [p3](benchmarks/20261119-140359-A-r150-A-pool10-p4base-p3.json) |
| 200 | 3 VALID | 12000 | 4.2 [4.1–4.2] | 22.8 [18.6–24.0] | 109.1 [56.5–187.8] | 24.3 [19.6–25.1] | 199.7 [199.7–199.7] | 0/0/0 | 3/56 (5%) | [p1](benchmarks/20261119-134808-A-r200-A-pool10-p4base-p1.json) [p2](benchmarks/20261119-135658-A-r200-A-pool10-p4base-p2.json) [p3](benchmarks/20261119-140545-A-r200-A-pool10-p4base-p3.json) |

### B: 10 conversations, single instance, pool 10 (Phase 4 step 0)

| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s | Hikari pending s | Lock share | Runs |
|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 3 VALID | 1500 | 16.6 [16.0–16.6] | 20.7 [20.7–20.9] | 30.5 [25.9–49.5] | 22.5 [22.2–22.5] | 24.9 [24.9–24.9] | 0/0/0 | 0/0 (0%) | [p1](benchmarks/20261119-140741-B-r25-B-pool10-p4base-p1.json) [p2](benchmarks/20261119-141814-B-r25-B-pool10-p4base-p2.json) [p3](benchmarks/20261119-142847-B-r25-B-pool10-p4base-p3.json) |
| 50 | 3 VALID | 3000 | 10.6 [10.5–11.0] | 16.4 [16.2–16.5] | 66.8 [53.9–76.6] | 18.0 [17.7–18.1] | 49.9 [49.9–49.9] | 0/0/0 | 0/21 (0%) | [p1](benchmarks/20261119-140926-B-r50-B-pool10-p4base-p1.json) [p2](benchmarks/20261119-141959-B-r50-B-pool10-p4base-p2.json) [p3](benchmarks/20261119-143032-B-r50-B-pool10-p4base-p3.json) |
| 100 | 3 VALID | 6000 | 6.7 [6.6–6.7] | 9.5 [9.4–9.7] | 41.6 [29.3–123.7] | 10.6 [10.5–10.8] | 99.8 [99.8–99.8] | 0/0/0 | 0/32 (0%) | [p1](benchmarks/20261119-141112-B-r100-B-pool10-p4base-p1.json) [p2](benchmarks/20261119-142145-B-r100-B-pool10-p4base-p2.json) [p3](benchmarks/20261119-143217-B-r100-B-pool10-p4base-p3.json) |
| 150 | 3 VALID | 9000 | 4.6 [4.6–4.6] | 9.6 [9.1–9.9] | 34.8 [26.6–35.0] | 10.7 [10.2–11.1] | 149.8 [149.8–149.8] | 0/0/0 | 0/44 (0%) | [p1](benchmarks/20261119-141257-B-r150-B-pool10-p4base-p1.json) [p2](benchmarks/20261119-142330-B-r150-B-pool10-p4base-p2.json) [p3](benchmarks/20261119-143403-B-r150-B-pool10-p4base-p3.json) |
| 200 | 3 VALID | 12000 | 4.2 [4.2–4.2] | 16.3 [15.7–19.5] | 142.2 [140.3–158.8] | 17.4 [16.6–20.9] | 199.7 [199.7–199.7] | 0/0/0 | 14/62 (23%) | [p1](benchmarks/20261119-141443-B-r200-B-pool10-p4base-p1.json) [p2](benchmarks/20261119-142515-B-r200-B-pool10-p4base-p2.json) [p3](benchmarks/20261119-143548-B-r200-B-pool10-p4base-p3.json) |
| 300 | 3 VALID | 18000 | 4.7 [4.6–4.7] | 31.5 [28.3–33.9] | 79.0 [64.5–82.6] | 32.9 [29.4–35.1] | 299.5 [299.5–299.6] | 0/0/0 | 55/160 (34%) | [p1](benchmarks/20261119-141628-B-r300-B-pool10-p4base-p1.json) [p2](benchmarks/20261119-142701-B-r300-B-pool10-p4base-p2.json) [p3](benchmarks/20261119-143734-B-r300-B-pool10-p4base-p3.json) |

| Point | Section 6 (Phase 5 code) | 7b (6b, AC) | **7c baseline (6b, clean)** |
|---|---|---|---|
| A 200/s: outcomes | 3 VALID | 2 VALID, 1 SATURATED (contended) | **3 VALID** |
| A 200/s: ack p99 | 16.5 [11.2–31.3] ms | 22.5 (clean pass) | **22.8 [18.6–24.0] ms** |
| B 300/s: outcomes | 3 VALID | 3 VALID | **3 VALID** |
| B 300/s: ack p99 | 31.2 [28.5–32.1] ms | 28.9 [28.4–32.9] ms | **31.5 [28.3–33.9] ms** |

**Reading it:**
- **The 6b busy-chat question is settled: no regression shown.** A at 200/s held in 3 of 3 runs, with p99 inside section 6's range; 7b's SATURATED run came from outside contention, not from the code.
- **B is unchanged** at every rate.
- **One unexplained difference:** at 25/s the ack p50 is about 16 ms (A 16.1, B 16.6), against 11.9 and 11.1 ms in section 6; 7b showed the same. It isn't there at 150/s and above (A 4.2 vs 3.8 ms at 200/s). Section 7 already noted that latency *falls* as the rate rises on this laptop (likely CPU power states between sparse sends); whether the extra ~4.5 ms at low rates is that or the Phase 6b pre-transaction queries was not isolated. It doesn't move the limits.
- **This is the baseline for Phase 4 step 5** (the same points with two instances).

## 7d. Phase 4 step 5: two instances, and the low-rate question (2026-11-19)

**Why:** compare two app instances (Redis bus and shared limits, ADR-0013) with the 7c baseline at the same points, and settle 7c's open question: is the extra ~4.5 ms ack p50 at 25/s the Phase 6b code or the laptop?

**How:** same harness, method, ladders and caveats as 7c (AC power, quiet-CPU wait, 3 passes, `ackTimeout` PT10M; only `chat-bench-pg` and, for the two-instance block, a bench Redis ran). Code: this branch after step 4 (`2eb6d3e`), plus the harness's second-instance option (`base2`, see below). In one session, back to back:
1. **Two instances** (labels `-p4two`): two JVMs on ports 8080 and 8081 (each `-Xmx1g`, pool 10, so 20 connections in total), `REALTIME_BUS=redis`, `RATELIMIT_STORE=redis`, Redis restarted per block. The harness alternates its sender (and C receiver) sockets between the instances, so in A half the senders of the one hot conversation are on each instance and every push to the other half crosses Redis. Counters (stored messages, acks) are summed over both instances.
2. **Pre-6b** (`527da77`, the last commit before Phase 6b; labels `-pre6b`): single instance, A and B at 25 and 50/s.
3. **Control** (this branch, single instance, no Redis; labels `-ctl`): the same 12 points, straight after 2.

**Clean:** 57 runs, no retries; 56 VALID, 1 SATURATED (two instances, A at 200/s, pass 1). Schedule-lag p99 ≤ 1.10 ms and Hikari pending 0 in every run.

**What this is not:** a scale-out measurement. Both JVMs, Postgres, Redis and the load generator share one laptop's CPUs, so two instances can't add capacity here; this measures what the bus and the shared limits cost, and whether anything breaks.

### A: hot conversation, two instances, pool 10 each (Phase 4 step 5)

| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s | Hikari pending s | Lock share | Runs |
|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 3 VALID | 1500 | 15.9 [15.5–16.9] | 22.2 [21.3–22.6] | 31.9 [26.9–47.9] | 24.6 [24.2–25.7] | 24.9 [24.9–24.9] | 0/0/0 | 0/7 (0%) | [p1](benchmarks/20261119-221126-A-r25-A-pool10-p4two-p1.json) [p2](benchmarks/20261119-222039-A-r25-A-pool10-p4two-p2.json) [p3](benchmarks/20261119-222950-A-r25-A-pool10-p4two-p3.json) |
| 50 | 3 VALID | 3000 | 9.8 [9.3–10.0] | 16.2 [15.9–30.8] | 101.9 [38.7–146.2] | 18.5 [18.3–33.1] | 49.9 [49.9–49.9] | 0/0/0 | 4/38 (11%) | [p1](benchmarks/20261119-221317-A-r50-A-pool10-p4two-p1.json) [p2](benchmarks/20261119-222229-A-r50-A-pool10-p4two-p2.json) [p3](benchmarks/20261119-223139-A-r50-A-pool10-p4two-p3.json) |
| 100 | 3 VALID | 6000 | 5.6 [5.6–5.8] | 12.1 [11.5–16.6] | 98.2 [52.8–156.5] | 14.1 [13.7–19.1] | 99.8 [99.8–99.8] | 0/0/0 | 5/55 (9%) | [p1](benchmarks/20261119-221507-A-r100-A-pool10-p4two-p1.json) [p2](benchmarks/20261119-222419-A-r100-A-pool10-p4two-p2.json) [p3](benchmarks/20261119-223329-A-r100-A-pool10-p4two-p3.json) |
| 150 | 3 VALID | 9000 | 5.2 [5.1–5.2] | 26.5 [19.6–69.3] | 284.7 [95.4–348.9] | 29.1 [22.4–72.3] | 149.7 [149.7–149.7] | 0/0/0 | 1/60 (2%) | [p1](benchmarks/20261119-221658-A-r150-A-pool10-p4two-p1.json) [p2](benchmarks/20261119-222610-A-r150-A-pool10-p4two-p2.json) [p3](benchmarks/20261119-223520-A-r150-A-pool10-p4two-p3.json) |
| 200 | 1 SATURATED, 2 VALID | 12000 | 5.4 [5.3–6.5] | 60.4 [38.6–1709.1] | 245.1 [112.3–2770.9] | 61.7 [40.0–1712.1] | 199.6 [199.6–201.2] | 0/0/0 | 164/241 (68%) | [p1](benchmarks/20261119-221849-A-r200-A-pool10-p4two-p1.json) [p2](benchmarks/20261119-222800-A-r200-A-pool10-p4two-p2.json) [p3](benchmarks/20261119-223710-A-r200-A-pool10-p4two-p3.json) |

### B: 10 conversations, two instances, pool 10 each (Phase 4 step 5)

| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s | Hikari pending s | Lock share | Runs |
|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 3 VALID | 1500 | 16.6 [13.5–17.9] | 22.8 [22.4–23.0] | 33.7 [33.0–144.6] | 25.5 [25.3–25.6] | 24.9 [24.9–24.9] | 0/0/0 | 0/9 (0%) | [p1](benchmarks/20261119-223923-B-r25-B-pool10-p4two-p1.json) [p2](benchmarks/20261119-225028-B-r25-B-pool10-p4two-p2.json) [p3](benchmarks/20261119-230131-B-r25-B-pool10-p4two-p3.json) |
| 50 | 3 VALID | 3000 | 9.6 [9.5–9.9] | 16.0 [15.4–16.0] | 41.4 [38.7–55.9] | 18.4 [18.1–18.7] | 49.9 [49.9–49.9] | 0/0/0 | 0/34 (0%) | [p1](benchmarks/20261119-224113-B-r50-B-pool10-p4two-p1.json) [p2](benchmarks/20261119-225219-B-r50-B-pool10-p4two-p2.json) [p3](benchmarks/20261119-230320-B-r50-B-pool10-p4two-p3.json) |
| 100 | 3 VALID | 6000 | 5.5 [5.5–5.6] | 11.5 [9.9–67.5] | 118.7 [36.0–484.4] | 14.0 [12.0–73.9] | 99.8 [99.8–99.8] | 0/0/0 | 2/25 (8%) | [p1](benchmarks/20261119-224304-B-r100-B-pool10-p4two-p1.json) [p2](benchmarks/20261119-225409-B-r100-B-pool10-p4two-p2.json) [p3](benchmarks/20261119-230511-B-r100-B-pool10-p4two-p3.json) |
| 150 | 3 VALID | 9000 | 5.0 [4.9–5.2] | 13.7 [13.6–19.9] | 97.5 [41.0–104.4] | 16.2 [16.0–22.9] | 149.7 [149.7–149.7] | 0/0/0 | 3/56 (5%) | [p1](benchmarks/20261119-224456-B-r150-B-pool10-p4two-p1.json) [p2](benchmarks/20261119-225559-B-r150-B-pool10-p4two-p2.json) [p3](benchmarks/20261119-230700-B-r150-B-pool10-p4two-p3.json) |
| 200 | 3 VALID | 12000 | 5.3 [5.3–5.3] | 25.8 [24.3–32.2] | 141.3 [64.9–182.8] | 28.5 [26.4–35.0] | 199.6 [199.6–199.6] | 0/0/0 | 16/95 (17%) | [p1](benchmarks/20261119-224647-B-r200-B-pool10-p4two-p1.json) [p2](benchmarks/20261119-225749-B-r200-B-pool10-p4two-p2.json) [p3](benchmarks/20261119-230851-B-r200-B-pool10-p4two-p3.json) |
| 300 | 3 VALID | 18000 | 10.2 [8.1–11.8] | 84.8 [60.3–250.4] | 201.7 [115.4–389.9] | 87.3 [63.1–255.7] | 299.3 [299.2–299.4] | 0/0/0 | 409/559 (73%) | [p1](benchmarks/20261119-224837-B-r300-B-pool10-p4two-p1.json) [p2](benchmarks/20261119-225939-B-r300-B-pool10-p4two-p2.json) [p3](benchmarks/20261119-231041-B-r300-B-pool10-p4two-p3.json) |

### A at 25 and 50/s: pre-6b code (527da77), single instance

| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s | Hikari pending s | Lock share | Runs |
|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 3 VALID | 1500 | 12.7 [11.4–13.1] | 16.4 [15.9–17.2] | 87.0 [23.8–124.9] | 18.0 [17.6–19.4] | 24.9 [24.9–24.9] | 0/0/0 | 1/2 (50%) | [p1](benchmarks/20261119-231240-A-r25-A-pool10-pre6b-p1.json) [p2](benchmarks/20261119-231611-A-r25-A-pool10-pre6b-p2.json) [p3](benchmarks/20261119-231941-A-r25-A-pool10-pre6b-p3.json) |
| 50 | 3 VALID | 3000 | 8.9 [8.7–9.6] | 14.1 [13.8–14.3] | 31.8 [26.0–91.8] | 15.7 [15.5–16.0] | 49.9 [49.9–49.9] | 0/0/0 | 0/6 (0%) | [p1](benchmarks/20261119-231426-A-r50-A-pool10-pre6b-p1.json) [p2](benchmarks/20261119-231756-A-r50-A-pool10-pre6b-p2.json) [p3](benchmarks/20261119-232126-A-r50-A-pool10-pre6b-p3.json) |

### A at 25 and 50/s: control, this branch, single instance (run right after pre-6b)

| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s | Hikari pending s | Lock share | Runs |
|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 3 VALID | 1500 | 15.6 [15.6–16.0] | 19.9 [19.4–20.4] | 30.7 [30.3–33.0] | 21.8 [21.2–22.2] | 24.9 [24.9–24.9] | 0/0/0 | 0/6 (0%) | [p1](benchmarks/20261119-233405-A-r25-A-pool10-ctl-p1.json) [p2](benchmarks/20261119-233736-A-r25-A-pool10-ctl-p2.json) [p3](benchmarks/20261119-234106-A-r25-A-pool10-ctl-p3.json) |
| 50 | 3 VALID | 3000 | 9.8 [9.8–10.0] | 15.8 [15.4–16.0] | 27.6 [22.8–32.8] | 17.3 [16.7–17.4] | 49.9 [49.9–49.9] | 0/0/0 | 0/20 (0%) | [p1](benchmarks/20261119-233551-A-r50-A-pool10-ctl-p1.json) [p2](benchmarks/20261119-233921-A-r50-A-pool10-ctl-p2.json) [p3](benchmarks/20261119-234252-A-r50-A-pool10-ctl-p3.json) |

### B at 25 and 50/s: pre-6b code (527da77), single instance

| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s | Hikari pending s | Lock share | Runs |
|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 3 VALID | 1500 | 13.4 [13.0–13.7] | 16.1 [16.1–16.6] | 43.9 [22.4–130.9] | 17.9 [17.9–18.0] | 24.9 [24.9–25.0] | 0/0/0 | 0/9 (0%) | [p1](benchmarks/20261119-232321-B-r25-B-pool10-pre6b-p1.json) [p2](benchmarks/20261119-232652-B-r25-B-pool10-pre6b-p2.json) [p3](benchmarks/20261119-233022-B-r25-B-pool10-pre6b-p3.json) |
| 50 | 3 VALID | 3000 | 9.1 [8.5–10.1] | 14.5 [14.4–14.7] | 61.2 [23.9–93.8] | 16.1 [16.0–16.3] | 49.9 [49.9–49.9] | 0/0/0 | 0/6 (0%) | [p1](benchmarks/20261119-232506-B-r50-B-pool10-pre6b-p1.json) [p2](benchmarks/20261119-232837-B-r50-B-pool10-pre6b-p2.json) [p3](benchmarks/20261119-233208-B-r50-B-pool10-pre6b-p3.json) |

### B at 25 and 50/s: control, this branch, single instance

| Rate/s | Outcomes | n acks/run | ack p50 | ack p99 | ack max | recv p99 | Server stored/s | Hikari pending s | Lock share | Runs |
|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 3 VALID | 1500 | 15.7 [15.5–15.9] | 19.6 [19.6–20.1] | 32.6 [23.2–39.5] | 21.3 [21.2–21.8] | 24.9 [24.9–24.9] | 0/0/0 | 0/0 (0%) | [p1](benchmarks/20261119-234448-B-r25-B-pool10-ctl-p1.json) [p2](benchmarks/20261119-234818-B-r25-B-pool10-ctl-p2.json) [p3](benchmarks/20261119-235149-B-r25-B-pool10-ctl-p3.json) |
| 50 | 3 VALID | 3000 | 10.0 [9.8–11.0] | 15.7 [15.6–16.0] | 32.3 [30.6–57.2] | 17.0 [17.0–17.4] | 49.9 [49.9–49.9] | 0/0/0 | 0/30 (0%) | [p1](benchmarks/20261119-234633-B-r50-B-pool10-ctl-p1.json) [p2](benchmarks/20261119-235003-B-r50-B-pool10-ctl-p2.json) [p3](benchmarks/20261119-235335-B-r50-B-pool10-ctl-p3.json) |

| Point (ack p50 / p99, ms; median of 3) | 7c baseline, one instance | **Two instances** |
|---|---|---|
| A 25/s | 16.1 / 20.6 | 15.9 / 22.2 |
| A 100/s | 6.6 / 9.9 | 5.6 / 12.1 |
| A 150/s | 4.7 / 9.8 | 5.2 / 26.5 |
| A 200/s | 4.2 / 22.8 (3 VALID) | 5.4 / 60.4 (**2 VALID, 1 SATURATED**) |
| B 25/s | 16.6 / 20.7 | 16.6 / 22.8 |
| B 100/s | 6.7 / 9.5 | 5.5 / 11.5 |
| B 200/s | 4.2 / 16.3 | 5.3 / 25.8 |
| B 300/s | 4.7 / 31.5 (3 VALID) | 10.2 / 84.8 (3 VALID) |

**Reading it:**
- **Up to 100/s, two instances look like one:** p50 within about 1 ms, p99 up to about 2 ms higher.
- **At 150/s and above the tail is clearly worse,** and A at 200/s, which was 3 of 3 VALID on one instance, was SATURATED once (ack p99 1.7 s; the other two passes held). The share of active Postgres samples waiting on a row lock rose too (A 200/s: 68% vs 5%; B 300/s: 73% vs 34%), so the hot rows were held longer, not just the app slower. **The cause wasn't isolated.** The likely candidates: two JVMs plus Redis competing with Postgres and the generator for the same CPUs (the machine's peak CPU sampled 0.86–1.0 in those runs, against 0.41–0.65 in 7c), and every message now crossing Redis and being pushed from the other instance. Concurrency is not the explanation: it's still 10 sender sockets (5 per instance), Hikari active max ≤ 7 on the first instance in the A 200/s and B 300/s runs.
- **So on this laptop two instances bought no throughput** and cost tail latency near the top of the ladder. That is expected on shared hardware; Phase 4 is for deploys without downtime and for surviving one instance, not for speed (ADR-0013). Whether two instances on separate hosts add throughput wasn't measured.
- **7c's low-rate question, answered: mostly the code.** In the same session, pre-6b vs this branch at 25/s: A ack p50 12.7 vs 15.6 ms, B 13.4 vs 15.7 ms (p99 16.4 vs 19.9 and 16.1 vs 19.6). At 50/s the gap is about 1 ms. So about 2.5–3 ms of the ~4.5 ms is in the code since 6b (the per-send work it added before the transaction: the duplicate pre-check, the send limit, the block/recipient check; which of them wasn't isolated), and the remaining ~1 ms (12.7 here vs 11.9 in section 6) is between sessions. The control on this branch (15.6/15.7) matches 7c on `main` (16.1/16.6), so Phase 4's local mode adds nothing visible. It disappears at higher rates, where p50 is 4–6 ms in every variant.

**Harness change for this section** ([`LoadMain`](../backend/src/test/java/com/chatplatform/load/LoadMain.java)): optional `base2=` (a second instance). Sockets alternate between instances, both are sampled, and server counters are summed over both (`CounterDeltasTest`; counting only the first instance would have marked every two-instance run SATURATED for storing "half" the messages, and that mutation fails the test). `hikariPendingSamples` is the larger of the two instances. `deploy/bench/run_ladder.py` gained `EXTRA_ARGS` to pass it.

## 7e. Phase 4 step 5: memory on two instances (2026-11-20)

**Why:** the server is a t3.small (2 GiB RAM, 2 GB swap). Before running two JVMs there, every container needs a memory limit, and the limits must fit with room for the host.

**How:** the production images (`chat-app`/`chat-web` built from this branch) with `compose.yml` + `compose.phase4.yml` + [`deploy/drill/compose.memory.yml`](../deploy/drill/compose.memory.yml) (ports on localhost for the harness, raised rate limits; own compose project). Per variant: 60 s idle, then B 100/s, B 200/s and C 10/s (200 sockets) with `base2` (both instances), then 60 s idle. `docker stats` and each JVM's `VmRSS` (`/proc/1/status`) every ~4 s. Docker Desktop VM on the laptop, not the server. "load" is the maximum seen during the load, the others the last sample.

| MiB | A: 2 × 768m, heap 75% (max 576) | B: 2 × 512m, heap 75% (max 384) | Step 5: 2 × 512m, heap 40% (max ~205) | **Final: + caps (below)** |
|---|---|---|---|---|
| app RSS idle / load / after | 298 / 404 / 404 | 285 / 372 / 372 | 280 / 362 / 363 | **275 / 346 / 346** |
| app2 RSS idle / load / after | 289 / 390 / 390 | 282 / 365 / 365 | 272 / 351 / 352 | **279 / 346 / 346** |
| app, app2 `docker stats` at load | 391, 373 | 354, 346 | 344, 334 | **329, 328** |
| db / redis / web at load | 100 / 10 / 20 | 101 / 12 / 22 | 100 / 12 / 21 | **98 / 10 / 22** |
| restarts, OOM kills | 0 | 0 | 0 | **0** |

**Where an app's RSS goes** (B at 200/s on the 512m stack, Micrometer every second, 91 samples per instance): heap used at most 76 MiB, heap committed 87 MiB, non-heap (metaspace, code cache, ...) 135 MiB, 66–78 threads. RSS minus committed heap stayed at 279–290 MiB: the JVM needs about 290 MiB **besides** its heap. So the image's `MaxRAMPercentage=75` doesn't fit a container: at 512m the heap could grow to 384 MiB, and 384 + 290 is about 670 MiB, so the kernel would kill the container before Java raised an OutOfMemoryError (at 768m: 576 + 290 = 866). The two-instance override caps the heap at 40% (about 205 MiB, 2.7 times the most it used): 205 + 290 = about 495 MiB, under 512m. That margin is thin, and it rests on this measurement of the non-heap part. The single-instance setting (768m, 75%) is unchanged by this phase; its heap has never come near its maximum, but the same arithmetic applies (HANDOFF open item).

**Limits chosen** (`compose.phase4.yml`): app 512m, app2 512m, db 320m, redis 96m, web 64m: **1504 MiB in total**, against a measured peak of about 0.8 GiB (811 MiB by `docker stats`) for all five. Postgres's limit is applied without a restart: `deploy.sh` never recreates `db` (ADR-0013 Decision 10), and the runbook sets it live with `docker update`. Variant A can't be used: 2 × 768m alone is 1.5 GiB, and with db, redis and web the limits would exceed what the t3.small can give (about 1.9 GiB visible to the OS, which itself, Docker and sshd need part of).

**Swap matters.** Docker's default lets a container swap as much again as its `mem_limit` (here `memory.swap.max` was 512M). In the first OOM drill the over-limit JVM was killed after about 2 minutes of load; in the second it was still crawling 3 minutes in (the load generator's lag p99 1.9 s) and wasn't killed. The apps therefore get `memswap_limit` = `mem_limit`: an app over its limit dies fast and restarts while the other instance serves. Postgres keeps the default on purpose: slower beats killed.

**OOM drill** (the chosen limits, no swap on the apps; `app2` given a JVM sized past its limit, `-Xms900m -Xmx900m` and once `-Xms640m -Xmx640m`, while the zero-failure probe ran through Caddy):
- `app2` was OOM-killed by the kernel (Docker `oom` event, `die` exit 137) during startup, and restarted by its restart policy in a loop (3 and 4 restarts) until it was put back on normal settings.
- `app`, `db`, `redis` and `web`: 0 restarts, start times unchanged.
- Probe: 431 requests / 0 failed, 94 of 94 acked sends in Postgres (first run); 494 / 0 failed, 107 of 107 (second run). Its WebSocket client was closed once when `app2` was replaced and reconnected with nothing missing.
- **Not shown with the probe running:** a kill in the middle of serving. Without swap, a JVM sized past 512m already crosses the limit while booting. With swap (Docker's default) one run did kill `app2` under load after about 2 minutes, with `app` and `db` untouched, but that run's probe never started (its origin wasn't allowed in the drill config; fixed).

### Follow-up: where the non-heap memory goes, and caps (2026-11-20)

**How:** the same stack with `-XX:NativeMemoryTracking=summary` and the summary printed at JVM exit (`PrintNMTStatistics`; the image is a JRE without `jcmd`). `app2` was stopped after 60 s idle; `app` took **all** the load (B 200/s, then C 10/s with 200 sockets), heavier per instance than the table above, and was stopped right after it. Committed MiB:

| | Idle (step-5 flags) | After load (step-5 flags) | After load (caps) |
|---|---|---|---|
| Java heap (max ~205) | 58 | 141 | 133 |
| Metaspace + class + CDS archive | 59 + 11 + 12 | 67 + 12 + 12 | 67 + 12 + 12 |
| Code cache | 26 | 50 | 39 |
| Symbols | 24 | 25 | 25 |
| Other (NIO direct buffers) | 8 | 12 | 8 |
| Compiler (C2; transient arena peak ~60) | 0.3 | 10 | 2 |
| NMT's own bookkeeping | 4 | 5 | 5 |
| Thread stacks (90+ threads; touched pages only) | 1.4 | 1.4 | 1.4 |
| GC | 0.2 | 0.5 | 0.5 |
| **Total committed / RSS** | **206 / 289** | **337 / 388** | **305 / 387** |

- **Besides the heap:** about 150–200 MiB is tracked (class metadata ~80, code ~40–50, symbols ~25, direct buffers ~10), and **50–90 MiB isn't tracked by NMT at all**: memory glibc keeps after the C2 compiler frees its arenas (peaks of ~60 MiB). Thread stacks are negligible (~15 KB touched per thread), so `-Xss` stays at the default.
- **GC:** inside a container below ~1.8 GiB, the JVM already picks **SerialGC** on its own (checked with `PrintFlagsFinal` at 512m and 768m), so every container run so far, and the live server, used SerialGC; only the host-JVM benchmarks (7c, 7d) used G1. GC overhead here is under 1 MiB.
- **Tomcat:** with the default 200, the busiest run created 56–60 worker threads (64 when capped at 64, still VALID).
- **Caps chosen** (`compose.phase4.yml`): `-XX:+UseSerialGC` (explicit, so raising a limit can't switch it), `-XX:ReservedCodeCacheSize=80m` (measured 39–50), `-XX:MaxMetaspaceSize=128m` (67), `-XX:MaxDirectMemorySize=64m` (≤ 12), `SERVER_TOMCAT_THREADS_MAX=64` (database work is bounded by the 10-connection pool, so more threads only queue; the cap limits how far a database stall multiplies threads). **They bound growth; they don't shrink what is used:** RSS with caps alone was 387 MiB in the heavy run, the same as without.
- **Tried and not adopted:** `MALLOC_ARENA_MAX=2`. One run peaked at 358 MiB, but its NMT showed the difference came mostly from a smaller committed heap at that moment, not from the untracked part; one run doesn't separate the two, and with 2 vCPUs the server has 16 arenas by default instead of the laptop VM's 64.
- **Result (the table's last column, the same method as before, load split over both instances):** peak RSS **346 MiB per app, 166 MiB below the 512m limit**; idle 275–279. Even with all the load on one instance, 387 MiB (125 below). All five containers peaked at 787 MiB (`docker stats`).
- **Latency with the caps:** one pass of the two-instance B ladder (25 … 300/s; host JVMs as in 7d, so the climb warms them the same way) with the capped flags (`-Xms205m -Xmx205m` for the 40%, SerialGC, the code cache, metaspace and direct-memory caps, Tomcat 64 threads; labels `-p4caps`). All 6 points VALID; no "CodeCache is full" or OutOfMemoryError in either app's log.

| B, two instances | 7d (G1, `-Xmx1g`, Tomcat 200), median [min–max] of 3 | **Capped flags, 1 run** |
|---|---|---|
| 300/s: outcome | 3 VALID | **VALID** |
| 300/s: ack p50 / p99 / max (ms) | 10.2 [8.1–11.8] / 84.8 [60.3–250.4] / 201.7 [115.4–389.9] | **7.9 / 66.4 / 185.9** |
| 300/s: stored/s, Hikari pending samples, lock share | 299.3, 0/0/0, 73% | **299.4, 1, 69%** |
| 200/s: ack p50 / p99 (ms) | 5.3 / 25.8 [24.3–32.2] | **5.2 / 23.1** |

  **No regression shown** at 300/s: the capped run sits inside step 5's range on every column. It's one run, against a spread that is wide at this rate (p99 60–250 ms), so it can rule out a large regression, not a small one. [Run files](benchmarks/20261120-014623-B-r300-B-pool10-p4caps-p1.json) (and the five lower rates beside it).

### Crash under load (2026-11-20)

The capped two-instance stack, the zero-failure probe through Caddy at **10 sends/s** plus 6 GETs/s and a WebSocket client:

| Event | Requests / failed | Acked sends / in Postgres / rows stored | `app2` back |
|---|---|---|---|
| `docker kill app2`, then `compose up -d app2` 15 s later; then a crash of `app2` (SIGKILL to its JVM from the Docker VM) | 1220 / **2** (one POST per kill, HTTP 502) | 716 / 716 / 716 | `docker kill`: **not restarted** (Docker treats it as a manual stop); after `compose up`: in Caddy's rotation at 8.6 s, Docker-healthy at 11.3 s. Crash: restarted by the policy in 0.9 s, in rotation at 9.0 s, healthy at 11.2 s |
| Crash of `app2`, then of `app` (which held the probe's socket) | 944 / **3** (1 + 2 POSTs, HTTP 502) | 560 / 560 / 560 | each restarted in ~1 s and healthy at ~11 s; the socket closed at +46.8 s, reconnected once to the other instance and converged (6 rows from catch-up, none missing) |

- **Why POSTs fail:** a send in flight on the instance that dies can't be answered, and Caddy doesn't resend a POST already forwarded (it can't know whether it took effect), so it returns 502. One more POST failed 0.6 s later on the `app` crash: most likely sent over Caddy's pooled connection before two missed health checks (1 s apart) took `app` out. GETs and later requests were retried on the other instance (`lb_try_duration`). **No failed send was stored** (rows = acked), and a client retrying with the same `clientMsgId` would be deduplicated anyway.
- `db`, `redis` and `web` never restarted.

## 8. Later scenarios (not in this phase)
- connection ramp;
- reconnect storm;
- `heads` polling cost at many conversations;
- deliberate slow consumers.
