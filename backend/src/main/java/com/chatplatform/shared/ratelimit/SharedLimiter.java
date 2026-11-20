package com.chatplatform.shared.ratelimit;

import com.chatplatform.shared.ratelimit.FixedWindowRateLimiter.Decision;
import com.chatplatform.shared.redis.RedisGuard;

import java.time.Duration;

/**
 * One limit (ADR-0013 Decision 4). With a Redis store, the window is shared by every instance; when Redis fails
 * or the breaker is open, the call falls back to this instance's in-memory window, so during an outage the limit
 * becomes per instance (up to N x instances in total; the outage table in ADR-0013). Without a store it is the
 * in-memory window only (one instance, ADR-0011).
 */
final class SharedLimiter {

    private final String name;
    private final int limit;
    private final long windowMillis;
    private final FixedWindowRateLimiter local;
    private final RedisRateLimitStore store;
    private final RedisGuard guard;

    SharedLimiter(String name, int limit, Duration window, int maxKeys, RedisRateLimitStore store, RedisGuard guard) {
        this.name = name;
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.local = new FixedWindowRateLimiter(limit, window, maxKeys, System::currentTimeMillis);
        this.store = store;
        this.guard = guard;
    }

    Decision tryAcquire(String key) {
        if (store == null) {
            return local.tryAcquire(key);
        }
        return guard.call(RedisGuard.Op.LIMIT, () -> {
            long[] r = store.increment(name, key, windowMillis);
            return r[0] <= limit ? new Decision(true, 0)
                    : new Decision(false, FixedWindowRateLimiter.retryAfter(r[1]));
        }, () -> local.tryAcquire(key));
    }

    boolean isBlocked(String key) {
        if (store == null) {
            return local.isBlocked(key);
        }
        return guard.call(RedisGuard.Op.LIMIT, () -> store.count(name, key) >= limit, () -> local.isBlocked(key));
    }

    void record(String key) {
        if (store == null) {
            local.record(key);
            return;
        }
        guard.call(RedisGuard.Op.LIMIT, () -> store.increment(name, key, windowMillis), () -> {
            local.record(key);
            return null;
        });
    }
}
