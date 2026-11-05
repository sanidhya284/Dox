package com.chatplatform.load;

import com.chatplatform.load.RunClassifier.Observations;
import com.chatplatform.load.RunClassifier.Outcome;
import com.chatplatform.load.RunClassifier.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunClassifierTest {

    private final RunClassifier classifier = new RunClassifier(10.0);

    /** Mutable test builder; starts as a healthy run: on target, all stored, low lag, flat latency. */
    static final class Obs {
        double target = 100.0, achieved = 99.5, stored = 99.5, lagP99 = 1.2, gc = 0.01;
        long lagSamples = 6000, ackTimeouts, outbox;
        int pending;
        double p50First = 2.0, p50Last = 2.1, ackP99 = 8.0;
        boolean rows = true, seq = true;

        Obs achieved(double v) { achieved = v; return this; }
        Obs stored(double v) { stored = v; return this; }
        Obs lag(double p99, long samples) { lagP99 = p99; lagSamples = samples; return this; }
        Obs gc(double v) { gc = v; return this; }
        Obs pending(int v) { pending = v; return this; }
        Obs ackTimeouts(long v) { ackTimeouts = v; return this; }
        Obs outbox(long v) { outbox = v; return this; }
        Obs p50Thirds(double first, double last) { p50First = first; p50Last = last; return this; }
        Obs ackP99(double v) { ackP99 = v; return this; }
        Obs rows(boolean v) { rows = v; return this; }
        Obs seq(boolean v) { seq = v; return this; }

        Observations build() {
            return new Observations(target, achieved, stored, lagP99, lagSamples, gc, pending, ackTimeouts, outbox,
                    p50First, p50Last, ackP99, rows, seq);
        }
    }

    private static Obs obs() {
        return new Obs();
    }

    private Verdict classify(Obs o) {
        return classifier.classify(o.build());
    }

    @Test
    void healthyRunIsValid() {
        Verdict v = classify(obs());
        assertThat(v.outcome()).isEqualTo(Outcome.VALID);
        assertThat(v.reasons()).isEmpty();
    }

    @Test
    void invariantViolationIsABugWhateverElseHappened() {
        Obs o = obs().achieved(50.0).lag(500.0, 6000).gc(0.2).pending(30).ackTimeouts(5).outbox(7)
                .p50Thirds(2.0, 900.0).rows(false);
        assertThat(classify(o).outcome()).isEqualTo(Outcome.INVARIANT_VIOLATION);
        assertThat(classify(obs().seq(false)).outcome()).isEqualTo(Outcome.INVARIANT_VIOLATION);
    }

    @Test
    void sustainedHikariPendingIsSaturated() {
        Verdict v = classify(obs().pending(3));
        assertThat(v.outcome()).isEqualTo(Outcome.SATURATED);
        assertThat(v.reasons()).anyMatch(r -> r.contains("Hikari pending"));
    }

    @Test
    void briefHikariPendingIsNotSaturation() {
        assertThat(classify(obs().pending(2)).outcome()).isEqualTo(Outcome.VALID);
    }

    @Test
    void ackTimeoutsOrAnUndrainedOutboxAreSaturated() {
        assertThat(classify(obs().ackTimeouts(1)).outcome()).isEqualTo(Outcome.SATURATED);
        assertThat(classify(obs().outbox(4)).outcome()).isEqualTo(Outcome.SATURATED);
    }

    @Test
    void latencyGrowingThroughTheWindowIsSaturated() {
        assertThat(classify(obs().p50Thirds(20.0, 120.0)).outcome()).isEqualTo(Outcome.SATURATED);
        assertThat(classify(obs().p50Thirds(2.0, 5.0)).outcome()).as("below the 50 ms floor").isEqualTo(Outcome.VALID);
    }

    @Test
    void scheduleLagWithAnIdleServerIsAHarnessFault() {
        Verdict v = classify(obs().lag(25.0, 6000));
        assertThat(v.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(v.reasons()).anyMatch(r -> r.contains("schedule lag"));
    }

    @Test
    void lowAchievedRateWithAnIdleServerIsAHarnessFault() {
        assertThat(classify(obs().achieved(90.0).stored(90.0)).outcome()).isEqualTo(Outcome.INVALID);
    }

    @Test
    void harnessGcShareAboveFivePercentIsAHarnessFault() {
        assertThat(classify(obs().gc(0.06)).outcome()).isEqualTo(Outcome.INVALID);
    }

    // ── Rules added after step 4 (BENCHMARKS.md, "Relabelling"). Values are taken from stored runs. ──

    @Test
    void serverStoringBelowNinetyFivePercentOfWhatWasSentIsSaturated() {
        // A-pool10 300/s pass 3 stored 269.1/s of 300/s sent; latency kept flat here to isolate this rule.
        Verdict v = classify(obs().achieved(300.0).stored(269.1));
        assertThat(v.outcome()).isEqualTo(Outcome.SATURATED);
        assertThat(v.reasons()).anyMatch(r -> r.contains("stored 269.1/s"));
        assertThat(classify(obs().achieved(300.0).stored(286.0)).outcome()).as("95.3%: kept up")
                .isEqualTo(Outcome.VALID);
    }

    @Test
    void backlogFormedDuringWarmUpIsCaughtByTheAckP99Ceiling() {
        // A-pool30 300/s pass 3: p50 grew only 1.74x (growth rule silent), stored 95.2% (stored rule silent).
        Verdict v = classify(obs().achieved(300.0).stored(285.7).p50Thirds(2633.73, 4595.71).ackP99(5533.70));
        assertThat(v.outcome()).isEqualTo(Outcome.SATURATED);
        assertThat(v.reasons()).anyMatch(r -> r.contains("ack p99 5533.7 ms > 1000.0 ms"));
        // The highest ack p99 of a run that kept up (A-pool10 300/s pass 2) stays VALID.
        assertThat(classify(obs().achieved(300.0).stored(299.6).ackP99(355.58)).outcome()).isEqualTo(Outcome.VALID);
        // An unsupported p99 (NaN: too few samples) never triggers the rule.
        assertThat(classify(obs().ackP99(Double.NaN)).outcome()).isEqualTo(Outcome.VALID);
    }

    @Test
    void tooFewLagSamplesForAP99IsReportedNotJudged() {
        // C 5/s pass 3: 300 lag samples (max 25.1 ms), so its p99 is unsupported (< 1000 samples).
        Verdict v = classify(obs().lag(Double.NaN, 300));
        assertThat(v.outcome()).isEqualTo(Outcome.VALID);
        assertThat(v.notes()).containsExactly("lag unsupported (n=300 < 1000)");
        assertThat(classify(obs()).notes()).as("supported lag: no note").isEmpty();
    }

    @Test
    void lagUnderServerBackPressureIsSaturatedNotInvalid() {
        // An overloaded server stops reading, the client's socket write blocks and lag follows.
        Verdict v = classify(obs().achieved(70.0).stored(70.0).lag(400.0, 6000).pending(10).ackTimeouts(3)
                .p50Thirds(20.0, 900.0));
        assertThat(v.outcome()).isEqualTo(Outcome.SATURATED);
        assertThat(v.reasons()).anyMatch(r -> r.contains("also: schedule lag"));
    }
}
