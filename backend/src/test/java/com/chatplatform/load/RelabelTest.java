package com.chatplatform.load;

import com.chatplatform.load.RunClassifier.Observations;
import com.chatplatform.load.RunClassifier.Outcome;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Field mapping from a stored summary (the shape the ladder runner writes) to the classifier inputs. */
class RelabelTest {

    /** Shaped like A-pool30 300/s pass 3 (recorded VALID at run time). */
    private static Map<String, Object> backloggedSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("outcome", "VALID");
        s.put("params", Map.of("targetRate", 300.0, "window", "PT1M", "lagThresholdMs", 10.0));
        s.put("sends", Map.of("achievedRate", 300.0, "resends", 0, "outboxRemaining", 0));
        s.put("latencyMs", Map.of(
                "sendToAck", Map.of("count", 18000, "p50", "3563.52", "p99", "5533.70"),
                "scheduleLag", Map.of("count", 18000, "p99", "0.43"),
                "ackP50ByWindowThird", List.of("2633.73", "3588.10", "4595.71")));
        s.put("harnessGcShare", 0.0);
        s.put("server", Map.of("hikariPendingSamples", 0));
        s.put("serverCounterDeltas", Map.of("messagesCreated", 17142.0));
        s.put("invariants", Map.of("rowsMatchAcked", true, "seqContiguous", true));
        return s;
    }

    @Test
    void mapsRecordedFieldsToClassifierInputs() {
        Observations o = Relabel.observations(backloggedSummary());
        assertThat(o.storedRate()).isEqualTo(17142.0 / 60.0);
        assertThat(o.ackP99Ms()).isEqualTo(5533.70);
        assertThat(o.ackP50FirstThirdMs()).isEqualTo(2633.73);
        assertThat(o.ackP50LastThirdMs()).isEqualTo(4595.71);
        assertThat(o.scheduleLagP99Ms()).isEqualTo(0.43);
        assertThat(o.scheduleLagSamples()).isEqualTo(18000);
    }

    @Test
    void backloggedRunRecordedValidIsRelabelledSaturated() {
        assertThat(Relabel.classify(backloggedSummary()).outcome()).isEqualTo(Outcome.SATURATED);
    }

    @Test
    void unsupportedPercentileStringsBecomeNaN() {
        assertThat(Relabel.num("n/a (n=300 < 1000)")).isNaN();
        assertThat(Relabel.num("25.10")).isEqualTo(25.10);
        assertThat(Relabel.num(7)).isEqualTo(7.0);
    }

    @Test
    void missingCounterDeltasLeaveTheStoredRateUnknown() {
        Map<String, Object> s = backloggedSummary();
        s.put("serverCounterDeltas", Map.of());
        assertThat(Relabel.observations(s).storedRate()).isNaN();
    }
}
