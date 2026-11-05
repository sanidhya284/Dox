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
 *       timeouts, an undrained outbox, or ack p50 in the last third of the window above 2x the first
 *       third and above 50 ms). Reported: it shows the knee. Harness signals seen at the same time are
 *       listed as "also:", since back-pressure (the server not reading) blocks the client's writes.</li>
 *   <li>INVALID: no server limit, but the harness fell behind: p99 schedule lag above the calibrated
 *       threshold, achieved rate below 95% of target, or harness GC above 5% of the window.</li>
 *   <li>VALID otherwise.</li>
 * </ul>
 * All thresholds are heuristics, stated in BENCHMARKS.md.
 */
final class RunClassifier {

    enum Outcome { VALID, SATURATED, INVALID, INVARIANT_VIOLATION }

    record Verdict(Outcome outcome, List<String> reasons) {
    }

    /**
     * @param hikariPendingSamples one-second samples in the window with Hikari pending > 0
     * @param ackTimeouts          sends retried because no ack came within the client's ack timeout
     * @param outboxRemaining      sends still unacked after the drain period
     */
    record Observations(double targetRate, double achievedRate, double scheduleLagP99Ms, double harnessGcShare,
                        int hikariPendingSamples, long ackTimeouts, long outboxRemaining,
                        double ackP50FirstThirdMs, double ackP50LastThirdMs,
                        boolean rowsMatchAcked, boolean seqContiguous) {
    }

    static final int PENDING_SAMPLES = 3;
    static final double GROWTH_FACTOR = 2.0;
    static final double GROWTH_FLOOR_MS = 50.0;
    static final double MIN_RATE_SHARE = 0.95;
    static final double MAX_GC_SHARE = 0.05;

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
            return new Verdict(Outcome.INVARIANT_VIOLATION, bugs);
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

        List<String> harness = new ArrayList<>();
        if (o.scheduleLagP99Ms() > lagThresholdMs) {
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
            return new Verdict(Outcome.SATURATED, reasons);
        }
        if (!harness.isEmpty()) {
            return new Verdict(Outcome.INVALID, harness);
        }
        return new Verdict(Outcome.VALID, List.of());
    }

    private static String fmt(String f, Object... args) {
        return String.format(Locale.ROOT, f, args);
    }
}
