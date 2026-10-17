package com.chatplatform.realtime.websocket;

import com.chatplatform.realtime.service.WsTicketService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handshake interceptor that authenticates WebSocket connection requests.
 *
 * <p>Validates origin against an allowed list, extracts and atomically consumes
 * the one-time ticket, and stores the resolved user ID in the session attributes.
 * All rejections return HTTP 401 because browsers cannot distinguish status codes
 * during WebSocket handshake failures.
 */
@Component
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "ws_user_id";
    public static final String TICKET_PARAM = "ticket";

    private final WsTicketService wsTicketService;
    private final List<String> allowedOrigins;

    public WsAuthHandshakeInterceptor(
            WsTicketService wsTicketService,
            @Value("${ws.allowed-origins:http://localhost:3000,http://localhost:5173}") List<String> allowedOrigins) {
        this.wsTicketService = wsTicketService;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        // Order reason: Origin validation must happen before ticket consumption so that
        // a bad-origin or cross-origin request does not consume a valid ticket,
        // preserving the ticket intact for a legitimate client retry.
        String origin = request.getHeaders().getOrigin();
        if (origin == null || !allowedOrigins.contains(origin)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // Extract ticket query parameter
        List<String> tickets = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .get(TICKET_PARAM);

        if (tickets == null || tickets.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String rawTicket = tickets.getFirst();
        if (rawTicket == null || rawTicket.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // Atomically consume ticket via WsTicketService
        Optional<UUID> userId = wsTicketService.consume(rawTicket);
        if (userId.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // Handshake succeeded: store resolved user ID in WebSocket attributes
        attributes.put(ATTR_USER_ID, userId.get());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // No-op
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }
}
