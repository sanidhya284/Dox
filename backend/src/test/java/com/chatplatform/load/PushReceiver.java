package com.chatplatform.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ObjLongConsumer;

/**
 * Receive-only socket for scenario C (fan-out): one user's login is shared by several of these, each
 * with its own ticket (amendment 6). It records when each pushed message arrives and how it was closed;
 * it sends nothing. Lighter than a ReferenceClient, which would also log in and poll heads per socket.
 */
final class PushReceiver extends TextWebSocketHandler {

    private final ObjectMapper mapper;
    private final ObjLongConsumer<String> onMessageBody;
    private final AtomicInteger closeCode = new AtomicInteger(-1);
    private volatile WebSocketSession session;

    PushReceiver(ObjectMapper mapper, ObjLongConsumer<String> onMessageBody) {
        this.mapper = mapper;
        this.onMessageBody = onMessageBody;
    }

    void connect(int port, String ticket) throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(64 * 1024);
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:3000");
        session = new StandardWebSocketClient(container)
                .execute(this, headers, URI.create("ws://localhost:" + port + "/ws?ticket=" + ticket))
                .get(10, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
        long at = System.nanoTime();
        JsonNode f = mapper.readTree(message.getPayload());
        if ("message".equals(f.path("type").asText())) {
            onMessageBody.accept(f.path("message").path("body").asText(), at);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        closeCode.set(status.getCode());
    }

    /** -1 while open (or closed by us before any callback). */
    int closeCode() {
        return closeCode.get();
    }

    void close() {
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } catch (Exception ignored) {
            // already gone
        }
    }
}
