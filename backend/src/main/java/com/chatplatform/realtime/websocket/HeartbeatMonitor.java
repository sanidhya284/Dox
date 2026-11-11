package com.chatplatform.realtime.websocket;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.PingMessage;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Heartbeats and idle detection (ADR-0007). Every {@code ws.heartbeat.interval} a sweep pings each
 * tracked socket (a protocol-level WebSocket ping; browsers and the Tomcat client answer with a pong
 * automatically) and closes, with 4006, any socket that has sent nothing (no pong, no frame) for
 * {@code ws.heartbeat.timeout}. Pings go through the registry's per-session decorator, serialised with
 * all other sends. The clock is injectable so the logic is unit-testable without waiting.
 */
@Component
public class HeartbeatMonitor {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private record Tracked(UUID userId, AtomicLong lastSeenNanos) {
    }

    private final ConnectionRegistry registry;
    private final TaskScheduler scheduler;
    private final Duration interval;
    private final long timeoutNanos;
    private final LongSupplier clock;
    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    private final AtomicLong pingsSent = new AtomicLong();
    private final AtomicLong pongsReceived = new AtomicLong();

    @Autowired
    public HeartbeatMonitor(
            ConnectionRegistry registry,
            @Qualifier(RealtimeSchedulingConfig.WS_TASK_SCHEDULER) TaskScheduler scheduler,
            @Value("${ws.heartbeat.interval:#{null}}") Duration interval,
            @Value("${ws.heartbeat.timeout:#{null}}") Duration timeout) {
        this(registry, scheduler, interval, timeout, System::nanoTime);
    }

    HeartbeatMonitor(ConnectionRegistry registry, TaskScheduler scheduler, Duration interval, Duration timeout,
                     LongSupplier clock) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalStateException("ws.heartbeat.interval must be a positive duration (e.g. PT25S), got: " + interval);
        }
        if (timeout == null || timeout.compareTo(interval.multipliedBy(2)) < 0) {
            throw new IllegalStateException("ws.heartbeat.timeout must be at least 2 x ws.heartbeat.interval ("
                    + interval.multipliedBy(2) + "), got: " + timeout
                    + " (one lost pong must not close a healthy socket)");
        }
        this.registry = registry;
        this.scheduler = scheduler;
        this.interval = interval;
        this.timeoutNanos = timeout.toNanos();
        this.clock = clock;
    }

    private boolean enabled = true;

    /** Off only in admin mode (runbook 9a), which serves no sockets. */
    @org.springframework.beans.factory.annotation.Value("${ws.heartbeat.enabled:true}")
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @PostConstruct
    void start() {
        if (enabled) {
            scheduler.scheduleAtFixedRate(this::sweepSafely, interval);
        }
    }

    public void track(UUID userId, String sessionId) {
        tracked.put(sessionId, new Tracked(userId, new AtomicLong(clock.getAsLong())));
    }

    /** Any inbound frame proves the connection is alive. */
    public void touch(String sessionId) {
        Tracked t = tracked.get(sessionId);
        if (t != null) {
            t.lastSeenNanos().set(clock.getAsLong());
        }
    }

    public void onPong(String sessionId) {
        pongsReceived.incrementAndGet();
        touch(sessionId);
    }

    public void forget(String sessionId) {
        tracked.remove(sessionId);
    }

    private void sweepSafely() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("Heartbeat sweep failed", e);
        }
    }

    void sweep() {
        long now = clock.getAsLong();
        for (Map.Entry<String, Tracked> e : tracked.entrySet()) {
            String sessionId = e.getKey();
            Tracked t = e.getValue();
            if (now - t.lastSeenNanos().get() > timeoutNanos) {
                tracked.remove(sessionId);
                log.info("WebSocket session {} idle for longer than the heartbeat timeout; closing with 4006", sessionId);
                registry.closeSession(t.userId(), sessionId, WsCloseStatuses.IDLE_TIMEOUT);
            } else if (registry.sendTo(t.userId(), sessionId, new PingMessage(EMPTY.duplicate()))) {
                pingsSent.incrementAndGet();
            }
        }
    }

    public long pingsSent() {
        return pingsSent.get();
    }

    public long pongsReceived() {
        return pongsReceived.get();
    }

    int trackedSessions() {
        return tracked.size();
    }
}
