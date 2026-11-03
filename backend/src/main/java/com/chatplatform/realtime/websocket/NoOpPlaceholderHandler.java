package com.chatplatform.realtime.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal placeholder WebSocketHandler for Phase 2b-i handshake authentication verification.
 *
 * <p>Contains zero logic beyond allowing the WebSocket handshake to complete for testing.
 * Session registry, connection lifecycle, and message handling are deferred to Phase 2b-ii.
 */
@Component
public class NoOpPlaceholderHandler extends TextWebSocketHandler {

    // Test-only observability seam: map growth is bounded by test count, discarded when the JVM exits,
    // and acceptable solely for test verification; this is not a pattern to carry into real handler code later.
    private final Map<String, UUID> ticketUsers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object userId = session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID);
        if (userId instanceof UUID u && session.getUri() != null) {
            List<String> tickets = UriComponentsBuilder.fromUri(session.getUri())
                    .build()
                    .getQueryParams()
                    .get(WsAuthHandshakeInterceptor.TICKET_PARAM);
            if (tickets != null && !tickets.isEmpty() && tickets.getFirst() != null) {
                ticketUsers.put(tickets.getFirst(), u);
            }
        }
    }

    public UUID getUserIdForTicket(String ticket) {
        return ticketUsers.get(ticket);
    }
}
