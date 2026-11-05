package com.chatplatform.realtime.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory map of userId to that user's open WebSocket sessions (one per tab or device).
 *
 * <p>Both {@link #register} and {@link #unregister} run inside {@link ConcurrentHashMap#compute},
 * which is atomic per key, so adding a user's first session and removing their last cannot interleave
 * (a two-step version loses sessions; see ConnectionRegistryTest).
 *
 * <p>Every send goes through a per-session {@link ConcurrentWebSocketSessionDecorator}: sending to one
 * session from several threads (acks, pushes) is unsafe, and the decorator serialises them with a
 * send-time limit and a buffer limit. When a limit trips or a write fails, the session is a slow or
 * broken consumer: it is closed with 4003 through the raw session (the decorator itself would rewrite
 * any close status to 4500 once its limit has tripped) and unregistered; the client recovers from
 * history. Raw sessions are never handed out; callers see session ids only.
 *
 * <p>Single instance only; Phase 4 needs a cross-instance fan-out.
 */
@Component
public class ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    static final int DEFAULT_SEND_TIME_LIMIT_MS = 5000;
    static final int DEFAULT_BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final ConcurrentHashMap<UUID, Map<String, ConcurrentWebSocketSessionDecorator>> sessionsByUser =
            new ConcurrentHashMap<>();
    private final int sendTimeLimitMs;
    private final int bufferSizeLimitBytes;

    /** Defaults; used by unit tests. */
    public ConnectionRegistry() {
        this(DEFAULT_SEND_TIME_LIMIT_MS, DEFAULT_BUFFER_SIZE_LIMIT_BYTES);
    }

    @Autowired
    public ConnectionRegistry(
            @Value("${ws.send.time-limit-ms:" + DEFAULT_SEND_TIME_LIMIT_MS + "}") int sendTimeLimitMs,
            @Value("${ws.send.buffer-bytes:" + DEFAULT_BUFFER_SIZE_LIMIT_BYTES + "}") int bufferSizeLimitBytes) {
        if (sendTimeLimitMs <= 0 || bufferSizeLimitBytes <= 0) {
            throw new IllegalStateException("ws.send.time-limit-ms and ws.send.buffer-bytes must be positive");
        }
        this.sendTimeLimitMs = sendTimeLimitMs;
        this.bufferSizeLimitBytes = bufferSizeLimitBytes;
    }

    public int sendTimeLimitMs() {
        return sendTimeLimitMs;
    }

    public void register(UUID userId, WebSocketSession session) {
        ConcurrentWebSocketSessionDecorator decorated = new ConcurrentWebSocketSessionDecorator(
                session, sendTimeLimitMs, bufferSizeLimitBytes,
                ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
        sessionsByUser.compute(userId, (id, sessions) -> {
            Map<String, ConcurrentWebSocketSessionDecorator> map = (sessions != null) ? sessions : new ConcurrentHashMap<>();
            map.put(session.getId(), decorated);
            return map;
        });
    }

    public void unregister(UUID userId, WebSocketSession session) {
        unregister(userId, session.getId());
    }

    private void unregister(UUID userId, String sessionId) {
        sessionsByUser.compute(userId, (id, sessions) -> {
            if (sessions == null) {
                return null;
            }
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions; // returning null removes the key atomically
        });
    }

    /** Ids of the user's currently registered sessions (a snapshot). */
    public Set<String> sessionIdsFor(UUID userId) {
        Map<String, ConcurrentWebSocketSessionDecorator> sessions = sessionsByUser.get(userId);
        return (sessions == null) ? Set.of() : Set.copyOf(sessions.keySet());
    }

    /** True while the user has an entry; an entry exists only while it holds at least one session. */
    public boolean hasEntry(UUID userId) {
        return sessionsByUser.containsKey(userId);
    }

    /** Closes one session with {@code status} (through the raw session) and unregisters it. */
    public void closeSession(UUID userId, String sessionId, CloseStatus status) {
        Map<String, ConcurrentWebSocketSessionDecorator> sessions = sessionsByUser.get(userId);
        ConcurrentWebSocketSessionDecorator target = (sessions == null) ? null : sessions.get(sessionId);
        unregister(userId, sessionId);
        if (target != null) {
            try {
                target.getDelegate().close(status);
            } catch (Exception e) {
                log.debug("Closing WebSocket session {} failed: {}", sessionId, e.toString());
            }
        }
    }

    /**
     * Sends to one session (an ack, an error frame, a heartbeat ping). False if it is not registered
     * or the send failed (a failed send closes the session with 4003).
     */
    public boolean sendTo(UUID userId, String sessionId, WebSocketMessage<?> message) {
        Map<String, ConcurrentWebSocketSessionDecorator> sessions = sessionsByUser.get(userId);
        ConcurrentWebSocketSessionDecorator target = (sessions == null) ? null : sessions.get(sessionId);
        if (target == null) {
            return false;
        }
        return send(userId, target, message);
    }

    /** Sends to every session of the given users, except {@code excludeSessionId} (may be null). */
    public void sendToUsers(Collection<UUID> userIds, TextMessage message, String excludeSessionId) {
        for (UUID userId : userIds) {
            Map<String, ConcurrentWebSocketSessionDecorator> sessions = sessionsByUser.get(userId);
            if (sessions == null) {
                continue;
            }
            for (ConcurrentWebSocketSessionDecorator target : List.copyOf(sessions.values())) {
                if (!target.getId().equals(excludeSessionId)) {
                    send(userId, target, message);
                }
            }
        }
    }

    private boolean send(UUID userId, ConcurrentWebSocketSessionDecorator target, WebSocketMessage<?> message) {
        try {
            target.sendMessage(message);
            return true;
        } catch (Exception e) { // SessionLimitExceededException, IOException, IllegalStateException
            log.warn("WebSocket session {} is a slow or broken consumer ({}); closing with 4003",
                    target.getId(), e.toString());
            closeSlowConsumer(userId, target);
            return false;
        }
    }

    private void closeSlowConsumer(UUID userId, ConcurrentWebSocketSessionDecorator target) {
        unregister(userId, target.getId());
        try {
            target.getDelegate().close(WsCloseStatuses.SLOW_CONSUMER); // raw session: keeps our 4003
        } catch (Exception e) {
            log.debug("Closing slow consumer {} failed: {}", target.getId(), e.toString());
        }
    }

    /**
     * Closes every session of the user with {@code status}. Iterates over a snapshot, because each
     * close calls back into {@link #unregister}. A failing close is logged and skipped so the
     * remaining sessions are still closed.
     */
    public void closeAll(UUID userId, CloseStatus status) {
        Map<String, ConcurrentWebSocketSessionDecorator> sessions = sessionsByUser.get(userId);
        if (sessions == null) {
            return;
        }
        for (ConcurrentWebSocketSessionDecorator session : List.copyOf(sessions.values())) {
            try {
                session.getDelegate().close(status);
            } catch (Exception e) {
                log.warn("Closing WebSocket session {} failed: {}", session.getId(), e.toString());
            }
        }
    }
}
