package com.chatplatform.load;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Classifies one measured run (docs/BENCHMARKS.md, "Run outcomes"):
 * <ul>
 *   <li>INVARIANT_VIOLATION: DB rows differ from the distinct acked ids, or seq has a gap. A bug, not
 *       a benchmark result; nothing else matters.</li>
 *   <li>SATURATED: the server reached a limit (Hikari pending in at least 3 one-second samples, ack
 *       timeouts, an undrained outbox, ack p50 in the last third of the window above 2x the first
 *       third and above 50 ms, the server storing below 95% of the sent rate, or ack p99 above
 *       1000 ms). Reported: it shows the limit. Harness signals seen at the same time are listed as
 *       "also:", since back-pressure (the server not reading) blocks the client's writes.</li>
 *   <li>INVALID: no server limit, but the harness fell behind: p99 schedule lag above the calibrated
 *       threshold, achieved rate below 95% of target, or harness GC above 5% of the window. With too
 *       few lag samples to support a p99, lag is not judged; a note says "lag unsupported".</li>
 *   <li>VALID otherwise.</li>
 * </ul>
 * All thresholds are heuristics, stated in BENCHMARKS.md.
 */
final class RunClassifier {

    enum Outcome { VALID, SATURATED, INVALID, INVARIANT_VIOLATION }

    /** {@code notes} are facts worth printing that do not change the outcome. */
    record Verdict(Outcome outcome, List<String> reasons, List<String> notes) {
    }

    /**
     * @param storedRate           messages the server created per second in the window (NaN: unknown)
     * @param scheduleLagP99Ms     p99 schedule lag, NaN when too few samples support a p99
     * @param scheduleLagSamples   schedule-lag samples in the window
     * @param hikariPendingSamples one-second samples in the window with Hikari pending > 0
     * @param ackTimeouts          sends retried because no ack came within the client's ack timeout
     * @param outboxRemaining      sends still unacked after the drain period
     * @param ackP99Ms             send-to-ack p99 over the window, NaN when too few samples support it
     */
    record Observations(double targetRate, double achievedRate, double storedRate,
                        double scheduleLagP99Ms, long scheduleLagSamples, double harnessGcShare,
                        int hikariPendingSamples, long ackTimeouts, long outboxRemaining,
                        double ackP50FirstThirdMs, double ackP50LastThirdMs, double ackP99Ms,
                        boolean rowsMatchAcked, boolean seqContiguous) {
    }

    static final int PENDING_SAMPLES = 3;
    static final double GROWTH_FACTOR = 2.0;
    static final double GROWTH_FLOOR_MS = 50.0;
    static final double MIN_RATE_SHARE = 0.95;
    static final double MAX_GC_SHARE = 0.05;
    /** Stored rate below this share of the sent rate means a backlog grew in the window. */
    static final double MIN_STORED_SHARE = 0.95;
    /**
     * Derived from the step-4 data (BENCHMARKS.md): the highest ack p99 of a run that kept up was 355.6 ms,
     * the lowest of a run that fell behind 5533.7 ms. 1000 ms sits between them. A heuristic.
     */
    static final double ACK_P99_CEILING_MS = 1000.0;

    private final double lagThresholdMs;

    RunClassifier(double lagThresholdMs) {
        this.lagThresholdMs = lagThresholdMs;
    }

    Verdict classify(Observations o) {
        List<String> bugs = new ArrayList<>();
        if (!o.rowsMatchAcked()) {
            bugs.add("DB rows != distinct acked clientMsgIds");
        }
        if (!o.seqContiguous()) {
            bugs.add("seq is not contiguous 1..N");
        }
        if (!bugs.isEmpty()) {
            return new Verdict(Outcome.INVARIANT_VIOLATION, bugs, List.of());
        }

        List<String> server = new ArrayList<>();
        if (o.hikariPendingSamples() >= PENDING_SAMPLES) {
            server.add("Hikari pending > 0 in " + o.hikariPendingSamples() + " samples");
        }
        if (o.ackTimeouts() > 0) {
            server.add(o.ackTimeouts() + " ack timeouts");
        }
        if (o.outboxRemaining() > 0) {
            server.add(o.outboxRemaining() + " sends unacked after drain");
        }
        if (o.ackP50LastThirdMs() > GROWTH_FACTOR * o.ackP50FirstThirdMs() && o.ackP50LastThirdMs() > GROWTH_FLOOR_MS) {
            server.add(fmt("ack p50 grew %.1f -> %.1f ms through the window", o.ackP50FirstThirdMs(), o.ackP50LastThirdMs()));
        }
        if (o.storedRate() < MIN_STORED_SHARE * o.achievedRate()) {
            server.add(fmt("server stored %.1f/s < 95%% of %.1f/s sent", o.storedRate(), o.achievedRate()));
        }
        if (o.ackP99Ms() > ACK_P99_CEILING_MS) {
            server.add(fmt("ack p99 %.1f ms > %.1f ms", o.ackP99Ms(), ACK_P99_CEILING_MS));
        }

        List<String> notes = new ArrayList<>();
        List<String> harness = new ArrayList<>();
        if (Double.isNaN(o.scheduleLagP99Ms())) {
            notes.add("lag unsupported (n=" + o.scheduleLagSamples() + " < " + LatencyStats.minSamples(99) + ")");
        } else if (o.scheduleLagP99Ms() > lagThresholdMs) {
            harness.add(fmt("schedule lag p99 %.1f ms > %.1f ms", o.scheduleLagP99Ms(), lagThresholdMs));
        }
        if (o.achievedRate() < MIN_RATE_SHARE * o.targetRate()) {
            harness.add(fmt("achieved %.1f/s < 95%% of target %.1f/s", o.achievedRate(), o.targetRate()));
        }
        if (o.harnessGcShare() > MAX_GC_SHARE) {
            harness.add(fmt("harness GC %.1f%% of the window", 100 * o.harnessGcShare()));
        }

        if (!server.isEmpty()) {
            List<String> reasons = new ArrayList<>(server);
            harness.forEach(h -> reasons.add("also: " + h));
            return new Verdict(Outcome.SATURATED, reasons, notes);
        }
        if (!harness.isEmpty()) {
            return new Verdict(Outcome.INVALID, harness, notes);
        }
        return new Verdict(Outcome.VALID, List.of(), notes);
    }

    private static String fmt(String f, Object... args) {
        return String.format(Locale.ROOT, f, args);
    }
}
