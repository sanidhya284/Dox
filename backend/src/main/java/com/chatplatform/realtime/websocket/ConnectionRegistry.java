package com.chatplatform.realtime.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory map of userId to that user's open WebSocket sessions (one per tab or device).
 *
 * <p>Both {@link #register} and {@link #unregister} run inside {@link ConcurrentHashMap#compute},
 * which is atomic per key. Adding a user's first session and removing their last one can therefore
 * not interleave: a two-step "add to the set, later remove the key if the set looks empty" can
 * remove a key just after another thread added to that set, losing (orphaning) that session.
 *
 * <p>Single-instance only: Phase 4 (several instances) needs a cross-instance fan-out. Raw sessions
 * are never handed out (see the 2c note in docs/HANDOFF.md about concurrent sends); tests and
 * callers see session ids only. Tickets and query strings are never stored or logged.
 */
@Component
public class ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    public void register(UUID userId, WebSocketSession session) {
        sessionsByUser.compute(userId, (id, sessions) -> {
            Set<WebSocketSession> set = (sessions != null) ? sessions : ConcurrentHashMap.newKeySet();
            set.add(session);
            return set;
        });
    }

    public void unregister(UUID userId, WebSocketSession session) {
        sessionsByUser.compute(userId, (id, sessions) -> {
            if (sessions == null) {
                return null;
            }
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions; // returning null removes the key atomically
        });
    }

    /** Ids of the user's currently registered sessions (a snapshot). */
    public Set<String> sessionIdsFor(UUID userId) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null) {
            return Set.of();
        }
        return sessions.stream().map(WebSocketSession::getId).collect(Collectors.toUnmodifiableSet());
    }

    /** True while the user has an entry; an entry exists only while it holds at least one session. */
    public boolean hasEntry(UUID userId) {
        return sessionsByUser.containsKey(userId);
    }

    /**
     * Closes every session of the user with {@code status}. Iterates over a snapshot, because each
     * close calls back into {@link #unregister}. A failing close is logged and skipped so the
     * remaining sessions are still closed.
     */
    public void closeAll(UUID userId, CloseStatus status) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : List.copyOf(sessions)) {
            try {
                session.close(status);
            } catch (Exception e) {
                log.warn("Closing WebSocket session {} failed: {}", session.getId(), e.toString());
            }
        }
    }
}
