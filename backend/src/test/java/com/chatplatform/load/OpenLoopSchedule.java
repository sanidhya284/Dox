package com.chatplatform.load;

/**
 * Open-loop (constant arrival rate) schedule: send i is INTENDED at start + i / rate, whatever the
 * server is doing. Computed from i each time, so rounding never accumulates into drift.
 */
final class OpenLoopSchedule {

    private final long startNanos;
    private final double ratePerSecond;

    OpenLoopSchedule(long startNanos, double ratePerSecond) {
        if (!(ratePerSecond > 0)) {
            throw new IllegalArgumentException("rate must be positive: " + ratePerSecond);
        }
        this.startNanos = startNanos;
        this.ratePerSecond = ratePerSecond;
    }

    long intendedNanos(long i) {
        return startNanos + Math.round(i * 1e9 / ratePerSecond);
    }

    /** How many sends are due at or before {@code nanos}. */
    long countDueBy(long nanos) {
        if (nanos < startNanos) {
            return 0;
        }
        long n = (long) Math.floor((nanos - startNanos) * ratePerSecond / 1e9) + 1;
        while (n > 0 && intendedNanos(n - 1) > nanos) {
            n--;
        }
        while (intendedNanos(n) <= nanos) {
            n++;
        }
        return n;
    }
}
