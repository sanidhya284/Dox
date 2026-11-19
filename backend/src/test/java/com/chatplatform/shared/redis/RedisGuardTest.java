package com.chatplatform.shared.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0013: the circuit breaker in front of every Redis call. It opens on ANY failure (a paused Redis keeps TCP
 * open, so Lettuce sees a command timeout, not a disconnect); while open, calls use the fallback without touching
 * Redis; after the open period exactly ONE caller probes (compare-and-set), everyone else stays on the fallback.
 */
class RedisGuardTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final RedisGuard guard = new RedisGuard(Duration.ofSeconds(5), now::get, new SimpleMeterRegistry());

    @Test
    void aCommandTimeoutOpensTheBreakerAndOpenMeansNoAttempts() {
        AtomicInteger redisCalls = new AtomicInteger();
        String first = guard.call(RedisGuard.Op.LIMIT, () -> {
            redisCalls.incrementAndGet();
            throw new QueryTimeoutException("Redis command timed out"); // what a paused Redis gives
        }, () -> "fallback");
        assertThat(first).isEqualTo("fallback");
        assertThat(guard.isOpen()).isTrue();

        for (int i = 0; i < 100; i++) {
            assertThat(guard.call(RedisGuard.Op.LIMIT, () -> {
                redisCalls.incrementAndGet();
                return "redis";
            }, () -> "fallback")).isEqualTo("fallback");
        }
        assertThat(redisCalls).as("open: no Redis attempts").hasValue(1);
    }

    @Test
    void afterTheOpenPeriodASuccessfulProbeClosesIt() {
        guard.call(RedisGuard.Op.LIMIT, () -> { throw new IllegalStateException("down"); }, () -> "fallback");
        now.addAndGet(5_001);
        assertThat(guard.call(RedisGuard.Op.LIMIT, () -> "redis", () -> "fallback")).isEqualTo("redis");
        assertThat(guard.isOpen()).isFalse();
        assertThat(guard.call(RedisGuard.Op.LIMIT, () -> "redis", () -> "fallback")).isEqualTo("redis");
    }

    @Test
    void aFailedProbeReopensForAnotherPeriod() {
        guard.call(RedisGuard.Op.LIMIT, () -> { throw new IllegalStateException("down"); }, () -> "fallback");
        now.addAndGet(5_001);
        guard.call(RedisGuard.Op.LIMIT, () -> { throw new IllegalStateException("still down"); }, () -> "fallback");
        assertThat(guard.isOpen()).isTrue();
        AtomicInteger calls = new AtomicInteger();
        guard.call(RedisGuard.Op.LIMIT, () -> calls.incrementAndGet(), () -> -1);
        assertThat(calls).as("reopened: no attempt").hasValue(0);
    }

    @Test
    void halfOpenAllowsExactlyOneProbeWhileEveryoneElseUsesTheFallback() throws Exception {
        guard.call(RedisGuard.Op.LIMIT, () -> { throw new IllegalStateException("down"); }, () -> "fallback");
        now.addAndGet(5_001);
        AtomicInteger probes = new AtomicInteger();
        CountDownLatch inProbe = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return guard.call(RedisGuard.Op.LIMIT, () -> {
                        probes.incrementAndGet();
                        inProbe.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS); // the probe is slow: the others must not wait
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "redis";
                    }, () -> "fallback");
                }));
            }
            go.countDown();
            assertThat(inProbe.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200); // everyone else has run by now
            release.countDown();
            long fallbacks = 0;
            for (Future<String> f : results) {
                fallbacks += "fallback".equals(f.get(5, TimeUnit.SECONDS)) ? 1 : 0;
            }
            assertThat(probes).as("exactly one probe").hasValue(1);
            assertThat(fallbacks).isEqualTo(15);
        } finally {
            pool.shutdownNow();
        }
    }
}
