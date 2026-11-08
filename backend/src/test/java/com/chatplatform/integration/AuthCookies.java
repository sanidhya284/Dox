package com.chatplatform.integration;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * The refresh token travels only in an HttpOnly cookie (ADR-0010). Tests read it from Set-Cookie and send
 * it back explicitly (TestRestTemplate ignores cookies), with an allowed Origin as a browser would.
 */
final class AuthCookies {

    static final String NAME = "chat_rt";
    static final String ORIGIN = "http://localhost:3000";

    private AuthCookies() {
    }

    /** The refresh-token cookie value set by this response, or null if none was set. */
    static String refreshToken(ResponseEntity<?> response) {
        String header = setCookie(response);
        if (header == null) {
            return null;
        }
        String value = header.substring(NAME.length() + 1);
        int end = value.indexOf(';');
        return end < 0 ? value : value.substring(0, end);
    }

    /** The full Set-Cookie header for the refresh cookie, or null. */
    static String setCookie(ResponseEntity<?> response) {
        List<String> all = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (all == null) {
            return null;
        }
        return all.stream().filter(h -> h.startsWith(NAME + "=")).findFirst().orElse(null);
    }

    static HttpEntity<Void> request(String refreshToken, String origin) {
        HttpHeaders h = new HttpHeaders();
        if (refreshToken != null) {
            h.add(HttpHeaders.COOKIE, NAME + "=" + refreshToken);
        }
        if (origin != null) {
            h.setOrigin(origin);
        }
        return new HttpEntity<>(null, h);
    }

    static ResponseEntity<String> refresh(TestRestTemplate rest, String refreshToken) {
        return rest.postForEntity("/api/auth/refresh", request(refreshToken, ORIGIN), String.class);
    }

    static ResponseEntity<String> logout(TestRestTemplate rest, String refreshToken) {
        return rest.postForEntity("/api/auth/logout", request(refreshToken, ORIGIN), String.class);
    }
}
