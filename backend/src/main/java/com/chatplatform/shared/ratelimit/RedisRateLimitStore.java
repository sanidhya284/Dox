package com.chatplatform.shared.ratelimit;

import com.chatplatform.shared.redis.RedisScripts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ratelimit.store=redis (ADR-0013 Decision 4): the fixed windows live in Redis, so a limit is N in total across
 * instances. Calls throw on any Redis error; {@link SharedLimiter} wraps them in the circuit breaker and falls back
 * to the instance's in-memory window.
 */
@Component
@ConditionalOnProperty(name = "ratelimit.store", havingValue = "redis")
public class RedisRateLimitStore {

    static final String PREFIX = "rl:";

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Counts one hit; returns {count in the window, ms left in the window}. */
    long[] increment(String limit, String key, long windowMillis) {
        List<?> r = redis.execute(RedisScripts.WINDOW_INCR, List.of(PREFIX + limit + ":" + key),
                String.valueOf(windowMillis));
        if (r == null || r.size() != 2) {
            throw new IllegalStateException("Unexpected rate-limit script result: " + r);
        }
        return new long[]{((Number) r.get(0)).longValue(), ((Number) r.get(1)).longValue()};
    }

    /** Hits in the current window, without counting. */
    long count(String limit, String key) {
        String v = redis.opsForValue().get(PREFIX + limit + ":" + key);
        return v == null ? 0 : Long.parseLong(v);
    }
}
