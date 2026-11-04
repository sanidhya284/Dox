package com.chatplatform.realtime.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.UUID;

/**
 * The /ws endpoint handler (Phase 2b-ii). Tracks each authenticated socket in the
 * {@link ConnectionRegistry} and forgets it on close or transport error.
 *
 * <p>Incoming frames are ignored for now: the frame protocol arrives in Phase 2c.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ConnectionRegistry registry;

    public ChatWebSocketHandler(ConnectionRegistry registry) {
        this.registry = registry;
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
        if (session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID) instanceof UUID userId) {
            registry.unregister(userId, session); // idempotent: transport error and close may both arrive
        }
    }
}
