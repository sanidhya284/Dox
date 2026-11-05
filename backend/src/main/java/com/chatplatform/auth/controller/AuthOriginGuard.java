package com.chatplatform.auth.controller;

import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CSRF defence for the cookie-authenticated endpoints, refresh and logout (ADR-0010). The cookie is
 * SameSite=Strict, and on top of that the request's Origin must exactly equal one of the app's own origins:
 * the same list that guards WebSocket handshakes (ws.allowed-origins, validated at startup). A request
 * without an Origin header is refused: browsers always send one on a POST.
 */
@Component
public class AuthOriginGuard {

    private final List<String> allowedOrigins;

    public AuthOriginGuard(@Value("${ws.allowed-origins:}") List<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    public void check(String origin) {
        if (origin == null || !allowedOrigins.contains(origin)) {
            throw new ApiException(ErrorCode.ORIGIN_NOT_ALLOWED);
        }
    }
}
