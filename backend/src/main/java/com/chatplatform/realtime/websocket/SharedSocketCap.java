package com.chatplatform.realtime.websocket;

import com.chatplatform.shared.redis.RedisGuard;
import com.chatplatform.shared.redis.RedisScripts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * ratelimit.store=redis: the per-user socket cap across instances (ADR-0013 Decision 6). One sorted set per user,
 * {@code chat:sockets:<userId>}; member = {@code <instance>:<session id>} (session ids are only unique per JVM);
 * score = expiry, {@code now + ws.heartbeat.timeout} in Redis time.
 *
 * <ul>
 *   <li>Admission is one Lua script (drop expired, check, add): atomic across instances.</li>
 *   <li>Each heartbeat sweep re-adds this instance's open sessions (plain ZADD), so live entries never expire, a
 *       crashed instance's entries expire within about timeout + interval, and the set rebuilds after a Redis
 *       restart.</li>
 *   <li>Every call goes through the circuit breaker. On failure, admission falls back to "allowed" and the
 *       registry's local cap applies: per instance during an outage (up to cap x instances).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "ratelimit.store", havingValue = "redis")
public class SharedSocketCap {

    static final String PREFIX = "chat:sockets:";

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final String ttlMillis;
    private final String instance = UUID.randomUUID().toString();
    private volatile boolean crashed;

    public SharedSocketCap(StringRedisTemplate redis, RedisGuard guard,
                           @Value("${ws.heartbeat.timeout:PT60S}") Duration timeout) {
        this.redis = redis;
        this.guard = guard;
        this.ttlMillis = String.valueOf(timeout.toMillis());
    }

    /** False only when Redis answered that the user already has {@code cap} live sockets. */
    boolean tryAdd(UUID userId, String sessionId, int cap) {
        if (crashed) {
            return true;
        }
        return guard.call(RedisGuard.Op.SOCKET_CAP,
                () -> !Long.valueOf(0).equals(redis.execute(RedisScripts.SOCKET_ADD, List.of(PREFIX + userId),
                        ttlMillis, String.valueOf(cap), member(sessionId))),
                () -> true);
    }

    void remove(UUID userId, String sessionId) {
        if (crashed) {
            return;
        }
        guard.run(RedisGuard.Op.SOCKET_CAP, () -> redis.opsForZSet().remove(PREFIX + userId, member(sessionId)));
    }

    /** Re-adds the given open sessions of one user with a fresh expiry. */
    void refresh(UUID userId, Collection<String> sessionIds) {
        if (crashed || sessionIds.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>(sessionIds.size() + 1);
        args.add(ttlMillis);
        for (String id : sessionIds) {
            args.add(member(id));
        }
        guard.run(RedisGuard.Op.REFRESH,
                () -> redis.execute(RedisScripts.SOCKET_REFRESH, List.of(PREFIX + userId), args.toArray()));
    }

    /**
     * Test seam (SocketCapCrashTest): from now on this instance neither refreshes nor removes its entries, as if
     * the process had died without closing anything.
     */
    public void simulateCrash() {
        crashed = true;
    }

    private String member(String sessionId) {
        return instance + ":" + sessionId;
    }
}
