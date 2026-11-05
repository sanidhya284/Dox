package com.chatplatform.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Exponential backoff with full jitter: delay = random[0, min(cap, base * 2^attempt)). */
class BackoffTest {

    private static final Duration BASE = Duration.ofMillis(100);
    private static final Duration CAP = Duration.ofSeconds(10);

    @Test
    void ceilingDoublesThenStaysAtTheCap() {
        Backoff b = new Backoff(BASE, CAP, new Random(1));
        assertThat(b.ceiling(0)).isEqualTo(Duration.ofMillis(100));
        assertThat(b.ceiling(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(b.ceiling(5)).isEqualTo(Duration.ofMillis(3200));
        assertThat(b.ceiling(7)).isEqualTo(CAP);        // 12.8 s capped
        assertThat(b.ceiling(1000)).isEqualTo(CAP);     // no overflow
    }

    @Test
    void delaysAreFullJitterWithinZeroAndTheCeiling() {
        Backoff b = new Backoff(BASE, CAP, new Random(42));
        for (int attempt = 0; attempt < 10; attempt++) {
            long ceiling = b.ceiling(attempt).toNanos();
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            for (int i = 0; i < 5000; i++) {
                b.resetTo(attempt);
                long d = b.nextDelay().toNanos();
                assertThat(d).isBetween(0L, ceiling - 1);
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            // Full jitter spreads over the whole range (not "ceiling plus a little noise").
            assertThat(min).as("attempt %d low end", attempt).isLessThan(ceiling / 10);
            assertThat(max).as("attempt %d high end", attempt).isGreaterThan(ceiling * 9 / 10);
        }
    }

    @Test
    void attemptsAdvanceAndResetGoesBackToTheBase() {
        Backoff b = new Backoff(BASE, CAP, new Random(7));
        b.nextDelay();
        b.nextDelay();
        b.nextDelay();
        assertThat(b.attempt()).isEqualTo(3);
        b.reset();
        assertThat(b.attempt()).isZero();
        assertThat(b.nextDelay()).isLessThan(BASE);
    }

    @Test
    void sameSeedGivesTheSameDelays() {
        Backoff x = new Backoff(BASE, CAP, new Random(99));
        Backoff y = new Backoff(BASE, CAP, new Random(99));
        for (int i = 0; i < 20; i++) {
            assertThat(x.nextDelay()).isEqualTo(y.nextDelay());
        }
    }
}
