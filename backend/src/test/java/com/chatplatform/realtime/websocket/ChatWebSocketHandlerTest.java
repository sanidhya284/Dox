package com.chatplatform.realtime.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWebSocketHandlerTest {

    private static final Duration LIFETIME = Duration.ofMinutes(15);

    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> lifetimeClose = mock(ScheduledFuture.class);
    private final com.chatplatform.realtime.websocket.protocol.FrameCodec codec =
            new com.chatplatform.realtime.websocket.protocol.FrameCodec(
                    org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build());
    private final ChatWebSocketHandler handler;

    ChatWebSocketHandlerTest() {
        doReturn(lifetimeClose).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        handler = new ChatWebSocketHandler(registry, scheduler, LIFETIME, codec,
                mock(com.chatplatform.message.service.MessageService.class), mock(HeartbeatMonitor.class),
                mock(com.chatplatform.conversation.service.ConversationService.class));
    }

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

    @Test
    void connectSchedulesTheLifetimeCloseAndCloseCancelsIt() throws Exception {
        UUID user = UUID.randomUUID();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(WsAuthHandshakeInterceptor.ATTR_USER_ID, user);
        WebSocketSession session = sessionWithAttributes("s4", attrs);

        Instant before = Instant.now();
        handler.afterConnectionEstablished(session);

        org.mockito.ArgumentCaptor<Instant> closeAt = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(any(Runnable.class), closeAt.capture());
        assertThat(closeAt.getValue()).isBetween(before.plus(LIFETIME), Instant.now().plus(LIFETIME));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        verify(lifetimeClose).cancel(anyBoolean());
    }

    @Test
    void lifetimeMustBeSetAndPositive() {
        for (Duration bad : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> new ChatWebSocketHandler(registry, scheduler, bad, codec,
                    mock(com.chatplatform.message.service.MessageService.class), mock(HeartbeatMonitor.class),
                    mock(com.chatplatform.conversation.service.ConversationService.class)))
                    .as("lifetime %s", bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ws.session.max-lifetime");
        }
    }
}
