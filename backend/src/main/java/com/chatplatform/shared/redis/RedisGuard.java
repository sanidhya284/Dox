package com.chatplatform.shared.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The circuit breaker in front of every Redis call (ADR-0013 Decision 5).
 *
 * <ul>
 *   <li><b>Opens on any failure</b>, command timeouts included: a paused Redis keeps its TCP connection open, so
 *       the client never sees a disconnect, only a {@code QueryTimeoutException} after the 200 ms command timeout.</li>
 *   <li><b>While open</b>, calls go straight to the caller's fallback (the per-instance limiter, the local socket
 *       cap, a dropped publish) without touching Redis: no per-request timeout wait during an outage.</li>
 *   <li><b>Half-open</b>: after the open period, exactly one caller wins a compare-and-set and probes Redis; every
 *       other caller keeps using the fallback until the probe settles. Success closes, failure reopens.</li>
 * </ul>
 * Metrics: {@code chat.redis.calls{op,result}} counts real Redis attempts, {@code chat.redis.fallback{op}} calls
 * answered by the fallback, {@code chat.redis.breaker.open} is 1 while not closed.
 */
@Component
public class RedisGuard {

    public enum Op { LIMIT, SOCKET_CAP, PUBLISH, REFRESH }

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private enum Permit { NORMAL, PROBE }

    private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);

    private final long openMillis;
    private final LongSupplier clock;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private volatile long openUntil;
    private final Map<Op, Counter> ok = new EnumMap<>(Op.class);
    private final Map<Op, Counter> errors = new EnumMap<>(Op.class);
    private final Map<Op, Counter> fallbacks = new EnumMap<>(Op.class);

    @Autowired
    public RedisGuard(MeterRegistry registry) {
        this(Duration.ofSeconds(5), System::currentTimeMillis, registry);
    }

    public RedisGuard(Duration openFor, LongSupplier clock, MeterRegistry registry) {
        this.openMillis = openFor.toMillis();
        this.clock = clock;
        for (Op op : Op.values()) {
            String tag = op.name().toLowerCase(Locale.ROOT);
            ok.put(op, Counter.builder("chat.redis.calls").description("Redis calls actually attempted")
                    .tag("op", tag).tag("result", "ok").register(registry));
            errors.put(op, Counter.builder("chat.redis.calls").description("Redis calls actually attempted")
                    .tag("op", tag).tag("result", "error").register(registry));
            fallbacks.put(op, Counter.builder("chat.redis.fallback")
                    .description("Calls answered by the per-instance fallback (breaker open or Redis error)")
                    .tag("op", tag).register(registry));
        }
        Gauge.builder("chat.redis.breaker.open", state, s -> s.get() == State.CLOSED ? 0 : 1)
                .description("1 while the Redis circuit breaker is open or half-open").register(registry);
    }

    /** Runs {@code redisCall} unless the breaker is open; any exception opens it and returns the fallback. */
    public <T> T call(Op op, Supplier<T> redisCall, Supplier<T> fallback) {
        Permit permit = tryAcquire();
        if (permit == null) {
            fallbacks.get(op).increment();
            return fallback.get();
        }
        T result;
        try {
            result = redisCall.get();
        } catch (RuntimeException e) {
            errors.get(op).increment();
            trip(op, permit, e);
            fallbacks.get(op).increment();
            return fallback.get();
        }
        ok.get(op).increment();
        if (permit == Permit.PROBE) {
            state.set(State.CLOSED);
            log.info("Redis is reachable again; circuit breaker closed");
        }
        return result;
    }

    /** As {@link #call}, for calls with no result: returns whether Redis was actually reached. */
    public boolean run(Op op, Runnable redisCall) {
        return call(op, () -> {
            redisCall.run();
            return Boolean.TRUE;
        }, () -> Boolean.FALSE);
    }

    public boolean isOpen() {
        return state.get() != State.CLOSED;
    }

    public long attempts(Op op) {
        return (long) (ok.get(op).count() + errors.get(op).count());
    }

    private Permit tryAcquire() {
        State s = state.get();
        if (s == State.CLOSED) {
            return Permit.NORMAL;
        }
        if (s == State.OPEN && clock.getAsLong() >= openUntil && state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
            return Permit.PROBE; // exactly one caller wins the CAS
        }
        return null;
    }

    private void trip(Op op, Permit permit, RuntimeException e) {
        openUntil = clock.getAsLong() + openMillis;
        State before = state.getAndSet(State.OPEN);
        if (before == State.CLOSED) {
            log.warn("Redis call ({}) failed, circuit breaker open for {} ms: {}", op, openMillis, e.toString());
        } else if (permit == Permit.PROBE) {
            log.debug("Redis probe failed, breaker reopened: {}", e.toString());
        }
    }
}
