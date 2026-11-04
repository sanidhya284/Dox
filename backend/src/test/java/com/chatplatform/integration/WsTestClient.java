package com.chatplatform.integration;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Test-only WebSocket client: collects every text frame received and how the server closed it. */
final class WsTestClient extends TextWebSocketHandler {

    final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
    final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private volatile WebSocketSession session;

    static WsTestClient connect(int port, String ticket) throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(64 * 1024); // receive large pushes (default is 8 KiB)
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:3000");
        WsTestClient client = new WsTestClient();
        client.session = new StandardWebSocketClient(container)
                .execute(client, headers, URI.create("ws://localhost:" + port + "/ws?ticket=" + ticket))
                .get(5, TimeUnit.SECONDS);
        return client;
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
        frames.add(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        closed.complete(status);
    }

    void send(String text) throws Exception {
        synchronized (this) { // one sender at a time on the client side
            session.sendMessage(new TextMessage(text));
        }
    }

    /** Next frame, or null if none arrives within the timeout. */
    String next(long timeout, TimeUnit unit) throws InterruptedException {
        return frames.poll(timeout, unit);
    }

    boolean isOpen() {
        return session.isOpen() && !closed.isDone();
    }

    void close() throws Exception {
        if (session.isOpen()) {
            session.close();
        }
    }
}
