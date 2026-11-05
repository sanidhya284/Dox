package com.chatplatform.load;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyStatsTest {

    @Test
    void minimumSamplesLeaveAtLeastTenObservationsAboveThePercentile() {
        assertThat(LatencyStats.minSamples(50)).isEqualTo(20);
        assertThat(LatencyStats.minSamples(95)).isEqualTo(200);
        assertThat(LatencyStats.minSamples(99)).isEqualTo(1000);
    }

    @Test
    void percentileIsWithheldBelowTheMinimumAndTheMaxIsAlwaysReported() {
        LatencyStats s = new LatencyStats();
        for (int i = 1; i <= 199; i++) {
            s.record(TimeUnit.MILLISECONDS.toNanos(i));
        }
        assertThat(s.count()).isEqualTo(199);
        assertThat(s.percentileMs(50)).isPresent();
        assertThat(s.percentileMs(95)).as("199 < 200 samples").isEmpty();
        assertThat(s.percentileMs(99)).isEmpty();
        assertThat(s.maxMs()).isBetween(198.0, 200.0); // HdrHistogram: 3 significant digits
        assertThat(s.describe(99)).startsWith("n/a (n=199 < 1000)");
    }

    @Test
    void percentilesAreIntendedTimeLatenciesWithoutExpectedIntervalCorrection() {
        LatencyStats s = new LatencyStats();
        for (int i = 0; i < 990; i++) {
            s.record(TimeUnit.MILLISECONDS.toNanos(1));
        }
        for (int i = 0; i < 10; i++) {
            s.record(TimeUnit.MILLISECONDS.toNanos(1000)); // one stall, recorded as it was observed
        }
        assertThat(s.count()).as("no synthetic samples were added").isEqualTo(1000);
        assertThat(s.percentileMs(50).getAsDouble()).isBetween(0.99, 1.01);
        assertThat(s.percentileMs(99).getAsDouble()).isBetween(0.99, 1.01);
        assertThat(s.maxMs()).isBetween(999.0, 1001.0);
    }

    @Test
    void negativeLatencyIsRecordedAsZero() {
        LatencyStats s = new LatencyStats();
        s.record(-5); // clock read before the intended instant: never a negative latency
        assertThat(s.maxMs()).isEqualTo(0.0);
    }
}
