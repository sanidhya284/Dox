package com.chatplatform.load;

import org.HdrHistogram.Histogram;

import java.util.OptionalDouble;

/**
 * Latency distribution (HdrHistogram, microseconds, 3 significant digits). Values are recorded exactly
 * as observed from the INTENDED send time (open loop), so queueing delay is already included; the
 * HdrHistogram expected-interval correction is deliberately NOT applied on top (docs/BENCHMARKS.md).
 *
 * <p>Minimum sample rule (a heuristic): a percentile p is reported only when at least 10 samples lie
 * above it, i.e. n >= ceil(10 / (1 - p/100)). Otherwise only the max is meaningful. Thread-safe.
 */
final class LatencyStats {

    private final Histogram histogram = new Histogram(3);

    static long minSamples(double percentile) {
        return (long) Math.ceil(10.0 / (1.0 - percentile / 100.0) - 1e-9);
    }

    synchronized void record(long latencyNanos) {
        histogram.recordValue(Math.max(0L, latencyNanos) / 1_000L);
    }

    synchronized long count() {
        return histogram.getTotalCount();
    }

    synchronized double maxMs() {
        return histogram.getTotalCount() == 0 ? 0.0 : histogram.getMaxValue() / 1000.0;
    }

    synchronized OptionalDouble percentileMs(double percentile) {
        if (histogram.getTotalCount() < minSamples(percentile)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(histogram.getValueAtPercentile(percentile) / 1000.0);
    }

    /** "12.3" (ms), or "n/a (n=199 < 1000)" when there are too few samples for this percentile. */
    String describe(double percentile) {
        OptionalDouble v = percentileMs(percentile);
        return v.isPresent() ? String.format(java.util.Locale.ROOT, "%.2f", v.getAsDouble())
                : "n/a (n=" + count() + " < " + minSamples(percentile) + ")";
    }
}
