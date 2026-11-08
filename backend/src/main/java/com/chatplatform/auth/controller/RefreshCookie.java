package com.chatplatform.auth.controller;

import com.chatplatform.shared.security.SecurityConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The refresh-token cookie (ADR-0010): HttpOnly (scripts can't read it), Secure (HTTPS only; relaxed only
 * by the dev profile), SameSite=Strict (never sent on cross-site requests), and scoped to /api/auth so the
 * browser sends it only to register, login, refresh and logout. Max-Age equals the token's lifetime.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "chat_rt";
    static final String PATH = "/api/auth";

    private final boolean secure;

    public RefreshCookie(@Value("${auth.refresh-cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie issue(String refreshToken) {
        return base(refreshToken).maxAge(Duration.ofMillis(SecurityConstants.REFRESH_TOKEN_EXPIRATION_MS)).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Strict").path(PATH);
    }
}
