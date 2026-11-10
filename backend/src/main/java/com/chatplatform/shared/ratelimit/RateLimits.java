package com.chatplatform.shared.ratelimit;

import com.chatplatform.shared.exception.RateLimitedException;
import com.chatplatform.shared.ratelimit.FixedWindowRateLimiter.Decision;
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
@org.springframework.boot.context.properties.EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimits {

    private final FixedWindowRateLimiter registerPerIp;
    private final FixedWindowRateLimiter loginPerIp;
    private final FixedWindowRateLimiter loginPerIpAndUser;
    private final FixedWindowRateLimiter ticketPerUser;
    private final FixedWindowRateLimiter failedHandshakesPerIp;
    private final FixedWindowRateLimiter searchPerUser;
    private final FixedWindowRateLimiter guestPerIp;
    private final FixedWindowRateLimiter signupGlobal;
    private final FixedWindowRateLimiter sendPerUser;
    private final FixedWindowRateLimiter sendPerGuest;
    private final FixedWindowRateLimiter dmPerUser;
    private final FixedWindowRateLimiter dmPerGuest;

    public RateLimits(RateLimitProperties p) {
        Duration minute = Duration.ofMinutes(1);
        int maxKeys = p.maxKeys();
        this.registerPerIp = limiter(p.registerPerIpPerHour(), Duration.ofHours(1), maxKeys);
        this.loginPerIp = limiter(p.loginPerIpPerMinute(), minute, maxKeys);
        this.loginPerIpAndUser = limiter(p.loginPerIpAndUserPerMinute(), minute, maxKeys);
        this.ticketPerUser = limiter(p.ticketPerUserPerMinute(), minute, maxKeys);
        this.failedHandshakesPerIp = limiter(p.failedHandshakesPerIpPerMinute(), minute, maxKeys);
        this.searchPerUser = limiter(p.searchPerUserPerMinute(), minute, maxKeys);
        this.guestPerIp = limiter(p.guestPerIpPerHour(), Duration.ofHours(1), maxKeys);
        this.signupGlobal = limiter(p.signupGlobalPerHour(), Duration.ofHours(1), 1);
        this.sendPerUser = limiter(p.sendPerUserPerMinute(), minute, maxKeys);
        this.sendPerGuest = limiter(p.sendPerGuestPerMinute(), minute, maxKeys);
        this.dmPerUser = limiter(p.dmPerUserPerHour(), Duration.ofHours(1), maxKeys);
        this.dmPerGuest = limiter(p.dmPerGuestPerHour(), Duration.ofHours(1), maxKeys);
    }

    /** Effectively no limits (unit tests that construct components by hand). */
    public static RateLimits unlimited() {
        return new RateLimits(RateLimitProperties.unlimited());
    }

    public void checkGuest(String ip) {
        enforce(guestPerIp.tryAcquire(ip));
    }

    /**
     * One counter for ALL sign-ups, whatever the IP (ADR-0012): bounds account creation from many IPs.
     * Fails closed: past the cap, registration answers 429 until the hour's window ends.
     */
    public void checkGlobalSignup() {
        enforce(signupGlobal.tryAcquire("all"));
    }

    /**
     * One send budget per user for WebSocket and REST together (ADR-0012). The caller checks it only for a
     * message that will be NEW: a resend of a committed clientMsgId is acked without using budget.
     */
    public void checkSend(UUID userId, boolean guest) {
        enforce((guest ? sendPerGuest : sendPerUser).tryAcquire(userId.toString()));
    }

    /** New DMs per user per hour; the caller checks it only when a DM was actually created. */
    public void checkNewDm(UUID userId, boolean guest) {
        enforce((guest ? dmPerGuest : dmPerUser).tryAcquire(userId.toString()));
    }

    public void checkSearch(UUID userId) {
        enforce(searchPerUser.tryAcquire(userId.toString()));
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
