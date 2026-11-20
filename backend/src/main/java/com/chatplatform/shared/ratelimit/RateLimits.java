package com.chatplatform.shared.ratelimit;

import com.chatplatform.shared.exception.RateLimitedException;
import com.chatplatform.shared.ratelimit.FixedWindowRateLimiter.Decision;
import com.chatplatform.shared.redis.RedisGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * The app's rate limits (ADR-0011). Callers check BEFORE the expensive or sensitive work (BCrypt, ticket
 * consumption). The client IP is {@code request.getRemoteAddr()}, which Tomcat's RemoteIpValve sets from
 * X-Forwarded-For only when the request comes from a trusted (private or loopback) proxy.
 *
 * <p>With {@code ratelimit.store=local} (default) every limit is per instance and in memory. With {@code redis},
 * each limit is shared by all instances, with a per-instance fallback while Redis is unreachable (ADR-0013). Many
 * users behind one NAT share an IP, so the per-IP limits apply to all of them together.
 */
@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimits {

    private final SharedLimiter registerPerIp;
    private final SharedLimiter loginPerIp;
    private final SharedLimiter loginPerIpAndUser;
    private final SharedLimiter ticketPerUser;
    private final SharedLimiter failedHandshakesPerIp;
    private final SharedLimiter searchPerUser;
    private final SharedLimiter guestPerIp;
    private final SharedLimiter signupGlobal;
    private final SharedLimiter sendPerUser;
    private final SharedLimiter sendPerGuest;
    private final SharedLimiter dmPerUser;
    private final SharedLimiter dmPerGuest;
    private final SharedLimiter botReplies;
    private final RedisRateLimitStore store;
    private final RedisGuard guard;

    /** In memory only (unit tests that construct components by hand). */
    public RateLimits(RateLimitProperties p) {
        this(p, (RedisRateLimitStore) null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimits(RateLimitProperties p, ObjectProvider<RedisRateLimitStore> store, RedisGuard guard) {
        this(p, store.getIfAvailable(), guard);
    }

    private RateLimits(RateLimitProperties p, RedisRateLimitStore store, RedisGuard guard) {
        this.store = store;
        this.guard = guard;
        Duration minute = Duration.ofMinutes(1);
        int maxKeys = p.maxKeys();
        this.registerPerIp = limiter("register", p.registerPerIpPerHour(), Duration.ofHours(1), maxKeys);
        this.loginPerIp = limiter("login", p.loginPerIpPerMinute(), minute, maxKeys);
        this.loginPerIpAndUser = limiter("login_user", p.loginPerIpAndUserPerMinute(), minute, maxKeys);
        this.ticketPerUser = limiter("ticket", p.ticketPerUserPerMinute(), minute, maxKeys);
        this.failedHandshakesPerIp = limiter("handshake_fail", p.failedHandshakesPerIpPerMinute(), minute, maxKeys);
        this.searchPerUser = limiter("search", p.searchPerUserPerMinute(), minute, maxKeys);
        this.guestPerIp = limiter("guest", p.guestPerIpPerHour(), Duration.ofHours(1), maxKeys);
        this.signupGlobal = limiter("signup", p.signupGlobalPerHour(), Duration.ofHours(1), 1);
        this.sendPerUser = limiter("send", p.sendPerUserPerMinute(), minute, maxKeys);
        this.sendPerGuest = limiter("send_guest", p.sendPerGuestPerMinute(), minute, maxKeys);
        this.dmPerUser = limiter("dm", p.dmPerUserPerHour(), Duration.ofHours(1), maxKeys);
        this.dmPerGuest = limiter("dm_guest", p.dmPerGuestPerHour(), Duration.ofHours(1), maxKeys);
        this.botReplies = limiter("bot", 1, Duration.ofSeconds(10), maxKeys);
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

    private SharedLimiter limiter(String name, int limit, Duration window, int maxKeys) {
        return new SharedLimiter(name, limit, window, maxKeys, store, guard);
    }

    /**
     * The welcome bot replies at most once per 10 s per conversation (ADR-0012). Shared, so two messages committed
     * on different instances still get one reply (ADR-0013); during a Redis outage it is per instance.
     */
    public boolean botReplyAllowed(UUID conversationId) {
        return botReplies.tryAcquire(conversationId.toString()).allowed();
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
