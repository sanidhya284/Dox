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
    private final com.chatplatform.shared.ratelimit.RateLimits rateLimits;

    @org.springframework.beans.factory.annotation.Autowired
    public WsAuthHandshakeInterceptor(
            WsTicketService wsTicketService,
            @Value("${ws.allowed-origins:}") List<String> allowedOrigins,
            com.chatplatform.shared.ratelimit.RateLimits rateLimits) {
        this.wsTicketService = wsTicketService;
        this.allowedOrigins = validate(allowedOrigins);
        this.rateLimits = rateLimits;
    }

    /** Unit tests: origin validation only, with no effective handshake limit. */
    WsAuthHandshakeInterceptor(WsTicketService wsTicketService, List<String> allowedOrigins) {
        this(wsTicketService, allowedOrigins, new com.chatplatform.shared.ratelimit.RateLimits(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1000));
    }

    /**
     * Fails closed, like the JWT secret: no hardcoded default, no wildcard, no blank entry.
     * Matching is exact (scheme, host, port), so a pattern would silently never match anyway.
     */
    private static List<String> validate(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            throw new IllegalStateException("ws.allowed-origins is not set. Set the WS_ALLOWED_ORIGINS environment variable "
                    + "(comma-separated exact origins), or activate the 'dev' profile for local development.");
        }
        for (String origin : origins) {
            if (origin == null || origin.isBlank()) {
                throw new IllegalStateException("ws.allowed-origins contains a blank entry.");
            }
            if (origin.contains("*")) {
                throw new IllegalStateException("ws.allowed-origins must list exact origins; wildcards are not allowed: " + origin);
            }
        }
        return List.copyOf(origins);
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
        // Too many failed handshakes from this IP: refuse before looking at the origin or any ticket,
        // so a blocked client can neither probe tickets nor consume one (ADR-0011).
        String ip = request.getRemoteAddress() == null ? "unknown"
                : request.getRemoteAddress().getAddress().getHostAddress();
        if (rateLimits.handshakeBlocked(ip)) {
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return false;
        }

        String origin = request.getHeaders().getOrigin();
        if (origin == null || !allowedOrigins.contains(origin)) {
            rateLimits.handshakeFailed(ip);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // Extract ticket query parameter
        List<String> tickets = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .get(TICKET_PARAM);

        if (tickets == null || tickets.isEmpty()) {
            rateLimits.handshakeFailed(ip);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String rawTicket = tickets.getFirst();
        if (rawTicket == null || rawTicket.isBlank()) {
            rateLimits.handshakeFailed(ip);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // Atomically consume ticket via WsTicketService
        Optional<UUID> userId = wsTicketService.consume(rawTicket);
        if (userId.isEmpty()) {
            rateLimits.handshakeFailed(ip);
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
