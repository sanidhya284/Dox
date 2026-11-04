package com.chatplatform.realtime.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * The /ws endpoint handler (Phase 2b-ii). Tracks each authenticated socket in the
 * {@link ConnectionRegistry}, closes it with 4001 after {@code ws.session.max-lifetime}, and forgets
 * it on close or transport error.
 *
 * <p>Incoming frames are ignored for now: the frame protocol arrives in Phase 2c.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    /** Session attribute holding the scheduled lifetime close, so a normal close can cancel it. */
    static final String ATTR_LIFETIME_CLOSE = "ws_lifetime_close";

    private final ConnectionRegistry registry;
    private final TaskScheduler scheduler;
    private final Duration maxLifetime;

    public ChatWebSocketHandler(
            ConnectionRegistry registry,
            @Qualifier(RealtimeSchedulingConfig.WS_TASK_SCHEDULER) TaskScheduler scheduler,
            @Value("${ws.session.max-lifetime:#{null}}") Duration maxLifetime) {
        this.registry = registry;
        this.scheduler = scheduler;
        if (maxLifetime == null || maxLifetime.isZero() || maxLifetime.isNegative()) {
            throw new IllegalStateException("ws.session.max-lifetime must be set to a positive duration "
                    + "(ISO-8601, e.g. PT15M; env WS_SESSION_MAX_LIFETIME), got: " + maxLifetime);
        }
        this.maxLifetime = maxLifetime;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!(session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID) instanceof UUID userId)) {
            // Should be impossible: WsAuthHandshakeInterceptor sets the attribute on every accepted
            // handshake. Fail closed rather than keep an unauthenticated socket.
            log.warn("WebSocket session {} has no authenticated user; closing with 1008", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        registry.register(userId, session);
        ScheduledFuture<?> lifetimeClose = scheduler.schedule(
                () -> closeAtEndOfLifetime(session), Instant.now().plus(maxLifetime));
        session.getAttributes().put(ATTR_LIFETIME_CLOSE, lifetimeClose);
    }

    private void closeAtEndOfLifetime(WebSocketSession session) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(WsCloseStatuses.SESSION_LIFETIME_REACHED);
        } catch (Exception e) {
            log.warn("Lifetime close of WebSocket session {} failed: {}", session.getId(), e.toString());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        unregister(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        unregister(session);
    }

    private void unregister(WebSocketSession session) {
        if (session.getAttributes().get(ATTR_LIFETIME_CLOSE) instanceof ScheduledFuture<?> lifetimeClose) {
            lifetimeClose.cancel(false); // a socket closed early must not leave its close task queued
        }
        if (session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID) instanceof UUID userId) {
            registry.unregister(userId, session); // idempotent: transport error and close may both arrive
        }
    }
}
