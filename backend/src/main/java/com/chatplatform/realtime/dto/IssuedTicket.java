package com.chatplatform.realtime.dto;

/**
 * Returned to the client after a successful POST /api/ws/ticket.
 * The {@code ticket} is the raw base64url value (no padding) that
 * must be passed as ?ticket=... on the WebSocket handshake.
 */
public record IssuedTicket(String ticket, int expiresInSeconds) {}
