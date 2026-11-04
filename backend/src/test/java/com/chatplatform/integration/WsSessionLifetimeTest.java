package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.realtime.service.WsTicketService;
import com.chatplatform.realtime.websocket.ConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Decision 5.1 A: the server closes every socket ws.session.max-lifetime after its handshake, with
 * code 4001. Runs with a 1-second lifetime, so it gets its own Spring context (same container).
 */
@TestPropertySource(properties = "ws.session.max-lifetime=PT1S")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
public class WsSessionLifetimeTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private ConnectionRegistry registry;

    /** Client side: remembers when (nanoTime) and how the server closed the socket. */
    private static final class CloseRecorder extends TextWebSocketHandler {
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        volatile long closedAtNanos;

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closedAtNanos = System.nanoTime();
            closed.complete(status);
        }
    }

    private UUID registerUser() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("life_" + s, "life_" + s + "@example.com", "password123", "Life " + s),
                AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().user().id();
    }

    private WebSocketSession connect(UUID userId, CloseRecorder recorder) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:3000");
        String ticket = wsTicketService.issue(userId).ticket();
        return new StandardWebSocketClient()
                .execute(recorder, headers, URI.create("ws://localhost:" + port + "/ws?ticket=" + ticket))
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void serverClosesTheSocketWith4001AfterItsLifetimeAndForgetsIt() throws Exception {
        UUID user = registerUser();
        CloseRecorder recorder = new CloseRecorder();

        long connectedAt = System.nanoTime();
        connect(user, recorder);

        CloseStatus status = recorder.closed.get(4, TimeUnit.SECONDS); // TimeoutException if never closed
        long afterMs = TimeUnit.NANOSECONDS.toMillis(recorder.closedAtNanos - connectedAt);

        assertThat(status.getCode()).as("close code").isEqualTo(4001);
        assertThat(afterMs).as("ms from connect to close (lifetime is 1 s)").isGreaterThanOrEqualTo(900);
        await("registry to drop the closed socket")
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(registry.hasEntry(user)).isFalse());
    }

    @Test
    void lifetimeIsCountedPerSocketFromItsOwnHandshake() throws Exception {
        UUID user = registerUser();
        CloseRecorder first = new CloseRecorder();
        CloseRecorder second = new CloseRecorder();

        connect(user, first);
        Thread.sleep(500);
        connect(user, second);

        assertThat(first.closed.get(4, TimeUnit.SECONDS).getCode()).isEqualTo(4001);
        assertThat(second.closed.get(4, TimeUnit.SECONDS).getCode()).isEqualTo(4001);
        long gapMs = TimeUnit.NANOSECONDS.toMillis(second.closedAtNanos - first.closedAtNanos);
        assertThat(gapMs).as("second socket (opened 500 ms later) closes later by about 500 ms").isGreaterThanOrEqualTo(300);
    }
}
