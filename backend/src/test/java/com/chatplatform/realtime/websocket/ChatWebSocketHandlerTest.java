package com.chatplatform.realtime.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWebSocketHandlerTest {

    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final ChatWebSocketHandler handler = new ChatWebSocketHandler(registry);

    private static WebSocketSession sessionWithAttributes(String id, Map<String, Object> attributes) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.getAttributes()).thenReturn(attributes);
        return s;
    }

    @Test
    void connectionWithoutAuthenticatedUserIsClosedWith1008AndNotRegistered() throws Exception {
        // Should be impossible (the handshake interceptor always sets the attribute), so fail closed.
        WebSocketSession session = sessionWithAttributes("s1", new HashMap<>());

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertThat(CloseStatus.POLICY_VIOLATION.getCode()).isEqualTo(1008);
    }

    @Test
    void connectionWithWrongTypedUserAttributeIsClosedWith1008() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(WsAuthHandshakeInterceptor.ATTR_USER_ID, "not-a-uuid");
        WebSocketSession session = sessionWithAttributes("s2", attrs);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void authenticatedConnectionIsRegisteredUnderItsUserAndRemovedOnClose() throws Exception {
        UUID user = UUID.randomUUID();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(WsAuthHandshakeInterceptor.ATTR_USER_ID, user);
        WebSocketSession session = sessionWithAttributes("s3", attrs);

        handler.afterConnectionEstablished(session);
        verify(session, never()).close(org.mockito.ArgumentMatchers.any());
        assertThat(registry.sessionIdsFor(user)).containsExactly("s3");

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(registry.hasEntry(user)).isFalse();
    }
}
