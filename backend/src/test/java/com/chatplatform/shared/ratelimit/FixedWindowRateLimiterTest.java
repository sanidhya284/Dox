package com.chatplatform.shared.ratelimit;

import com.chatplatform.shared.ratelimit.FixedWindowRateLimiter.Decision;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private FixedWindowRateLimiter limiter(int limit, int maxKeys) {
        return new FixedWindowRateLimiter(limit, Duration.ofSeconds(60), maxKeys, now::get);
    }

    @Test
    void allowsExactlyTheLimitPerWindowThenDeniesWithRetryAfter() {
        FixedWindowRateLimiter l = limiter(3, 100);
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        now.addAndGet(20_000);
        Decision denied = l.tryAcquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(40); // window started 20 s ago, 60 s long
    }

    @Test
    void aNewWindowAllowsAgain() {
        FixedWindowRateLimiter l = limiter(1, 100);
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isFalse();
        now.addAndGet(60_000);
        assertThat(l.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void keysAreIndependent() {
        FixedWindowRateLimiter l = limiter(1, 100);
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("b").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void recordAndIsBlockedCountFailuresWithoutConsumingOnCheck() {
        FixedWindowRateLimiter l = limiter(2, 100);
        assertThat(l.isBlocked("ip")).isFalse();
        assertThat(l.isBlocked("ip")).as("checking does not count").isFalse();
        l.record("ip");
        assertThat(l.isBlocked("ip")).isFalse();
        l.record("ip");
        assertThat(l.isBlocked("ip")).isTrue();
        now.addAndGet(60_000);
        assertThat(l.isBlocked("ip")).isFalse();
    }

    @Test
    void expiredWindowsAreEvictedSoMemoryStaysBounded() {
        FixedWindowRateLimiter l = limiter(5, 3);
        l.tryAcquire("a");
        l.tryAcquire("b");
        l.tryAcquire("c");
        assertThat(l.trackedKeys()).isEqualTo(3);
        now.addAndGet(60_000); // all three windows expired
        assertThat(l.tryAcquire("d").allowed()).as("full map: expired keys are evicted first").isTrue();
        assertThat(l.trackedKeys()).isEqualTo(1);
    }

    @Test
    void whenFullOfLiveKeysANewKeyIsRefusedButKnownKeysStillWork() {
        FixedWindowRateLimiter l = limiter(5, 2);
        l.tryAcquire("a");
        l.tryAcquire("b");
        Decision refused = l.tryAcquire("c");
        assertThat(refused.allowed()).as("fail closed instead of growing without bound").isFalse();
        assertThat(refused.retryAfterSeconds()).isPositive();
        assertThat(l.trackedKeys()).isEqualTo(2);
        assertThat(l.tryAcquire("a").allowed()).isTrue();
    }
}
