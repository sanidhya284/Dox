package com.chatplatform.client;

import java.time.Duration;
import java.util.Random;

/**
 * Exponential backoff with FULL jitter (ADR-0008): each delay is uniform in
 * [0, min(cap, base * 2^attempt)). Full jitter spreads reconnecting clients over the whole window, so
 * clients dropped together do not reconnect together. Not thread-safe; one per client.
 */
public final class Backoff {

    private final Duration base;
    private final Duration cap;
    private final Random random;
    private int attempt;

    public Backoff(Duration base, Duration cap, Random random) {
        if (base.isZero() || base.isNegative() || cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("need 0 < base <= cap");
        }
        this.base = base;
        this.cap = cap;
        this.random = random;
    }

    /** Upper bound (exclusive) of the delay for a given attempt; overflow-safe. */
    public Duration ceiling(int attempt) {
        long baseNanos = base.toNanos();
        long capNanos = cap.toNanos();
        if (attempt >= 62 || baseNanos > (capNanos >> Math.min(attempt, 62))) {
            return cap;
        }
        return Duration.ofNanos(Math.min(capNanos, baseNanos << attempt));
    }

    /** The next delay; advances the attempt counter. */
    public Duration nextDelay() {
        long ceiling = ceiling(attempt).toNanos();
        attempt++;
        return Duration.ofNanos((long) (random.nextDouble() * ceiling));
    }

    /** After a successful connect. */
    public void reset() {
        attempt = 0;
    }

    void resetTo(int attempt) {
        this.attempt = attempt;
    }

    public int attempt() {
        return attempt;
    }
}
