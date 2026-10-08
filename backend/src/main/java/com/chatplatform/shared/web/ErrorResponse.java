package com.chatplatform.shared.web;

import java.time.Instant;

/**
 * The single error response format for all API errors.
 *
 * Clients can rely on this shape for error handling.
 * Internal details (SQL, stack traces, passwords) never appear here.
 */
public record ErrorResponse(
        String code,
        String message,
        String requestId,
        Instant timestamp
) {}
