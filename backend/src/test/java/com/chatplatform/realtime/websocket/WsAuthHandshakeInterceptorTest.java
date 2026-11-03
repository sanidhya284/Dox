package com.chatplatform.realtime.websocket;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The origin allowlist must fail closed: a misconfigured list refuses startup
 * instead of silently allowing everything (or a hardcoded default).
 */
class WsAuthHandshakeInterceptorTest {

    private static void assertRejected(List<String> origins) {
        assertThatThrownBy(() -> new WsAuthHandshakeInterceptor(null, origins))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ws.allowed-origins");
    }

    @Test
    void nullOrEmptyListIsRejected() {
        assertRejected(null);
        assertRejected(List.of());
    }

    @Test
    void wildcardIsRejected() {
        assertRejected(List.of("*"));
        assertRejected(List.of("http://localhost:3000", "*"));
        assertRejected(List.of("https://*.example.com"));
    }

    @Test
    void blankOrNullEntryIsRejected() {
        assertRejected(List.of("http://localhost:3000", ""));
        assertRejected(List.of("   "));
        assertRejected(Arrays.asList("http://localhost:3000", null));
    }

    @Test
    void explicitOriginsAreAccepted() {
        WsAuthHandshakeInterceptor interceptor =
                new WsAuthHandshakeInterceptor(null, List.of("http://localhost:3000", "https://app.example.com"));
        assertThat(interceptor.getAllowedOrigins())
                .containsExactly("http://localhost:3000", "https://app.example.com");
    }
}
