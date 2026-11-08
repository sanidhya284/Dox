package com.chatplatform.realtime.controller;

import com.chatplatform.realtime.dto.IssuedTicket;
import com.chatplatform.realtime.service.WsTicketService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Issues one-time WebSocket authentication tickets.
 *
 * The caller must present a valid Bearer JWT (enforced by Spring Security;
 * no special configuration needed — the route is not in the public allow-list).
 * The returned ticket should be passed as {@code ?ticket=...} on the WebSocket
 * handshake within its validity window (default 30 seconds).
 */
@RestController
@RequestMapping("/api/ws")
public class WsTicketController {

    private final WsTicketService wsTicketService;
    private final com.chatplatform.shared.ratelimit.RateLimits rateLimits;

    public WsTicketController(WsTicketService wsTicketService, com.chatplatform.shared.ratelimit.RateLimits rateLimits) {
        this.wsTicketService = wsTicketService;
        this.rateLimits = rateLimits;
    }

    /**
     * POST /api/ws/ticket
     *
     * @param currentUserId injected from the validated JWT by {@link com.chatplatform.auth.security.JwtAuthFilter}
     * @return the raw ticket and its TTL in seconds
     */
    @PostMapping("/ticket")
    public IssuedTicket issueTicket(@AuthenticationPrincipal UUID currentUserId) {
        rateLimits.checkTicket(currentUserId);
        return wsTicketService.issue(currentUserId);
    }
}
