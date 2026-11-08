package com.chatplatform.shared.ratelimit;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory fixed-window counter per key (ADR-0011). Single instance only: in Phase 4 the counters move
 * to Redis. Memory is bounded: when {@code maxKeys} keys are tracked, expired windows are evicted first;
 * if every tracked window is still live, a NEW key is refused (fail closed) rather than growing the map.
 *
 * <p>Two ways to use it: {@link #tryAcquire} counts every attempt (register, login, ticket);
 * {@link #isBlocked} + {@link #record} count only failures (WebSocket handshakes), checking without counting.
 */
public final class FixedWindowRateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    /** start of the window (ms) and attempts in it; replaced, never mutated. */
    private record Window(long startMillis, int count) {
    }

    private final int limit;
    private final long windowMillis;
    private final int maxKeys;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, Duration window, int maxKeys, LongSupplier clockMillis) {
        if (limit <= 0 || window.isNegative() || window.isZero() || maxKeys <= 0) {
            throw new IllegalArgumentException("limit, window and maxKeys must be positive");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.maxKeys = maxKeys;
        this.clock = clockMillis;
    }

    /** Counts this attempt; denied once {@code limit} attempts were made in the current window. */
    public Decision tryAcquire(String key) {
        long now = clock.getAsLong();
        if (!windows.containsKey(key) && !makeRoom(now)) {
            return new Decision(false, retryAfter(windowMillis));
        }
        Window w = windows.compute(key, (k, old) -> (old == null || expired(old, now))
                ? new Window(now, 1) : new Window(old.startMillis(), old.count() + 1));
        if (w.count() <= limit) {
            return new Decision(true, 0);
        }
        return new Decision(false, retryAfter(w.startMillis() + windowMillis - now));
    }

    /** True when {@code limit} failures were recorded in the current window. Does not count. */
    public boolean isBlocked(String key) {
        Window w = windows.get(key);
        return w != null && !expired(w, clock.getAsLong()) && w.count() >= limit;
    }

    /** Records one failure for {@code key}. When the map is full of live windows, the failure is dropped. */
    public void record(String key) {
        long now = clock.getAsLong();
        if (!windows.containsKey(key) && !makeRoom(now)) {
            return;
        }
        windows.compute(key, (k, old) -> (old == null || expired(old, now))
                ? new Window(now, 1) : new Window(old.startMillis(), old.count() + 1));
    }

    public int trackedKeys() {
        return windows.size();
    }

    /** Evicts expired windows when the map is full; false if it is still full afterwards. */
    private boolean makeRoom(long now) {
        if (windows.size() < maxKeys) {
            return true;
        }
        for (Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator(); it.hasNext(); ) {
            if (expired(it.next().getValue(), now)) {
                it.remove();
            }
        }
        return windows.size() < maxKeys;
    }

    private boolean expired(Window w, long now) {
        return now - w.startMillis() >= windowMillis;
    }

    private static long retryAfter(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }
}
