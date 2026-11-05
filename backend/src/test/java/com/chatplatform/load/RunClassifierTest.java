package com.chatplatform.load;

import com.chatplatform.load.RunClassifier.Observations;
import com.chatplatform.load.RunClassifier.Outcome;
import com.chatplatform.load.RunClassifier.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunClassifierTest {

    private final RunClassifier classifier = new RunClassifier(10.0);

    /** A healthy run: on target, low lag, nothing pending, latency flat, invariants hold. */
    private static Observations healthy() {
        return new Observations(100.0, 99.5, 1.2, 0.01, 0, 0, 0, 2.0, 2.1, true, true);
    }

    @Test
    void healthyRunIsValid() {
        Verdict v = classifier.classify(healthy());
        assertThat(v.outcome()).isEqualTo(Outcome.VALID);
        assertThat(v.reasons()).isEmpty();
    }

    @Test
    void invariantViolationIsABugWhateverElseHappened() {
        Observations o = new Observations(100.0, 50.0, 500.0, 0.2, 30, 5, 7, 2.0, 900.0, false, true);
        assertThat(classifier.classify(o).outcome()).isEqualTo(Outcome.INVARIANT_VIOLATION);
        Observations seqGap = new Observations(100.0, 99.5, 1.2, 0.01, 0, 0, 0, 2.0, 2.1, true, false);
        assertThat(classifier.classify(seqGap).outcome()).isEqualTo(Outcome.INVARIANT_VIOLATION);
    }

    @Test
    void sustainedHikariPendingIsSaturated() {
        Observations o = new Observations(100.0, 99.5, 1.2, 0.01, 3, 0, 0, 2.0, 2.1, true, true);
        Verdict v = classifier.classify(o);
        assertThat(v.outcome()).isEqualTo(Outcome.SATURATED);
        assertThat(v.reasons()).anyMatch(r -> r.contains("Hikari pending"));
    }

    @Test
    void briefHikariPendingIsNotSaturation() {
        Observations o = new Observations(100.0, 99.5, 1.2, 0.01, 2, 0, 0, 2.0, 2.1, true, true);
        assertThat(classifier.classify(o).outcome()).isEqualTo(Outcome.VALID);
    }

    @Test
    void ackTimeoutsOrAnUndrainedOutboxAreSaturated() {
        assertThat(classifier.classify(new Observations(100.0, 99.5, 1.2, 0.01, 0, 1, 0, 2.0, 2.1, true, true))
                .outcome()).isEqualTo(Outcome.SATURATED);
        assertThat(classifier.classify(new Observations(100.0, 99.5, 1.2, 0.01, 0, 0, 4, 2.0, 2.1, true, true))
                .outcome()).isEqualTo(Outcome.SATURATED);
    }

    @Test
    void latencyGrowingThroughTheWindowIsSaturated() {
        Observations o = new Observations(100.0, 99.5, 1.2, 0.01, 0, 0, 0, 20.0, 120.0, true, true);
        assertThat(classifier.classify(o).outcome()).isEqualTo(Outcome.SATURATED);
        Observations smallButDoubled = new Observations(100.0, 99.5, 1.2, 0.01, 0, 0, 0, 2.0, 5.0, true, true);
        assertThat(classifier.classify(smallButDoubled).outcome()).as("below the 50 ms floor").isEqualTo(Outcome.VALID);
    }

    @Test
    void scheduleLagWithAnIdleServerIsAHarnessFault() {
        Observations o = new Observations(100.0, 99.5, 25.0, 0.01, 0, 0, 0, 2.0, 2.1, true, true);
        Verdict v = classifier.classify(o);
        assertThat(v.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(v.reasons()).anyMatch(r -> r.contains("schedule lag"));
    }

    @Test
    void lowAchievedRateWithAnIdleServerIsAHarnessFault() {
        Observations o = new Observations(100.0, 90.0, 1.2, 0.01, 0, 0, 0, 2.0, 2.1, true, true);
        assertThat(classifier.classify(o).outcome()).isEqualTo(Outcome.INVALID);
    }

    @Test
    void harnessGcShareAboveFivePercentIsAHarnessFault() {
        Observations o = new Observations(100.0, 99.5, 1.2, 0.06, 0, 0, 0, 2.0, 2.1, true, true);
        assertThat(classifier.classify(o).outcome()).isEqualTo(Outcome.INVALID);
    }

    @Test
    void lagUnderServerBackPressureIsSaturatedNotInvalid() {
        // An overloaded server stops reading, the client's socket write blocks and lag follows.
        Observations o = new Observations(100.0, 70.0, 400.0, 0.01, 10, 3, 0, 20.0, 900.0, true, true);
        Verdict v = classifier.classify(o);
        assertThat(v.outcome()).isEqualTo(Outcome.SATURATED);
        assertThat(v.reasons()).anyMatch(r -> r.contains("also: schedule lag"));
    }
}
