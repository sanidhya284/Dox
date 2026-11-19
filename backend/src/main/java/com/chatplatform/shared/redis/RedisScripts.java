package com.chatplatform.shared.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/** The Lua scripts behind the shared limits and the socket cap (ADR-0013 Decisions 4 and 6). Each is atomic. */
public final class RedisScripts {

    private RedisScripts() {
    }

    /**
     * Fixed window counter. KEYS[1] = {@code rl:<limit>:<key>}, ARGV[1] = window in ms. The first hit of a window
     * sets the expiry. Returns {count, ms left in the window}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final RedisScript<List> WINDOW_INCR = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local t = redis.call('PTTL', KEYS[1])
            if t < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              t = tonumber(ARGV[1])
            end
            return {c, t}
            """, List.class);

    /**
     * Socket cap admission. KEYS[1] = {@code chat:sockets:<userId>}; ARGV[1] = ttl ms, ARGV[2] = cap, ARGV[3] =
     * member ({@code <instance>:<session>}). Score = expiry in Redis time; expired members are dropped first, so a
     * crashed instance's sockets stop counting once their score passes. Check and add in one script. Returns 1 if
     * admitted, 0 if the cap is reached.
     */
    public static final RedisScript<Long> SOCKET_ADD = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            if redis.call('ZSCORE', KEYS[1], ARGV[3]) == false
                and redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
              return 0
            end
            local expiry = now + tonumber(ARGV[1])
            redis.call('ZADD', KEYS[1], expiry, ARGV[3])
            local top = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
            redis.call('PEXPIREAT', KEYS[1], top[2])
            return 1
            """, Long.class);

    /**
     * Heartbeat refresh. KEYS[1] = {@code chat:sockets:<userId>}; ARGV[1] = ttl ms, ARGV[2..] = members this
     * instance knows are open. Plain ZADD (not XX), so after a Redis restart this rebuilds the set. The key expires
     * with its largest score.
     */
    public static final RedisScript<Long> SOCKET_REFRESH = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local expiry = now + tonumber(ARGV[1])
            for i = 2, #ARGV do
              redis.call('ZADD', KEYS[1], expiry, ARGV[i])
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            local top = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
            if top[2] then
              redis.call('PEXPIREAT', KEYS[1], top[2])
            end
            return #ARGV - 1
            """, Long.class);
}
