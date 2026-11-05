package com.chatplatform.shared.ratelimit;

import com.chatplatform.shared.exception.RateLimitedException;
import com.chatplatform.shared.ratelimit.FixedWindowRateLimiter.Decision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * The app's rate limits (ADR-0011). Callers check BEFORE the expensive or sensitive work (BCrypt, ticket
 * consumption). The client IP is {@code request.getRemoteAddr()}, which Tomcat's RemoteIpValve sets from
 * X-Forwarded-For only when the request comes from a trusted (private or loopback) proxy.
 *
 * <p>All limits are per instance and in memory (Phase 4 moves them to Redis). Many users behind one
 * NAT share an IP, so the per-IP limits apply to all of them together.
 */
@Component
public class RateLimits {

    private final FixedWindowRateLimiter registerPerIp;
    private final FixedWindowRateLimiter loginPerIp;
    private final FixedWindowRateLimiter loginPerIpAndUser;
    private final FixedWindowRateLimiter ticketPerUser;
    private final FixedWindowRateLimiter failedHandshakesPerIp;

    public RateLimits(
            @Value("${ratelimit.register-per-ip-per-hour:5}") int registerPerIpPerHour,
            @Value("${ratelimit.login-per-ip-per-minute:20}") int loginPerIpPerMinute,
            @Value("${ratelimit.login-per-ip-and-user-per-minute:5}") int loginPerIpAndUserPerMinute,
            @Value("${ratelimit.ticket-per-user-per-minute:30}") int ticketPerUserPerMinute,
            @Value("${ratelimit.failed-handshakes-per-ip-per-minute:20}") int failedHandshakesPerIpPerMinute,
            @Value("${ratelimit.max-keys:100000}") int maxKeys) {
        Duration minute = Duration.ofMinutes(1);
        this.registerPerIp = limiter(registerPerIpPerHour, Duration.ofHours(1), maxKeys);
        this.loginPerIp = limiter(loginPerIpPerMinute, minute, maxKeys);
        this.loginPerIpAndUser = limiter(loginPerIpAndUserPerMinute, minute, maxKeys);
        this.ticketPerUser = limiter(ticketPerUserPerMinute, minute, maxKeys);
        this.failedHandshakesPerIp = limiter(failedHandshakesPerIpPerMinute, minute, maxKeys);
    }

    private static FixedWindowRateLimiter limiter(int limit, Duration window, int maxKeys) {
        return new FixedWindowRateLimiter(limit, window, maxKeys, System::currentTimeMillis);
    }

    public void checkRegister(String ip) {
        enforce(registerPerIp.tryAcquire(ip));
    }

    /** Per IP and per (IP, username): one guessed account can't be hammered, nor many from one IP. */
    public void checkLogin(String ip, String username) {
        enforce(loginPerIp.tryAcquire(ip));
        String user = username == null ? "" : username.toLowerCase(Locale.ROOT);
        enforce(loginPerIpAndUser.tryAcquire(ip + "|" + user.substring(0, Math.min(user.length(), 100))));
    }

    public void checkTicket(UUID userId) {
        enforce(ticketPerUser.tryAcquire(userId.toString()));
    }

    /** Too many failed WebSocket handshakes from this IP recently: reject before touching any ticket. */
    public boolean handshakeBlocked(String ip) {
        return failedHandshakesPerIp.isBlocked(ip);
    }

    public void handshakeFailed(String ip) {
        failedHandshakesPerIp.record(ip);
    }

    private static void enforce(Decision d) {
        if (!d.allowed()) {
            throw new RateLimitedException(d.retryAfterSeconds());
        }
    }
}
