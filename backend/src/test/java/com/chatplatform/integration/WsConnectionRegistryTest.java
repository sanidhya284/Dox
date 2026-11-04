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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Phase 2b-ii: the server tracks every open socket per user and forgets it on close. */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
public class WsConnectionRegistryTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private ConnectionRegistry registry;

    private UUID registerUser() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("reg_" + s, "reg_" + s + "@example.com", "password123", "Reg " + s),
                AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().user().id();
    }

    private WebSocketSession connect(UUID userId) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:3000");
        String ticket = wsTicketService.issue(userId).ticket();
        return new StandardWebSocketClient()
                .execute(new TextWebSocketHandler(), headers, URI.create("ws://localhost:" + port + "/ws?ticket=" + ticket))
                .get(5, TimeUnit.SECONDS);
    }

    private void awaitSessionCount(UUID userId, int expected) {
        await("user " + userId + " to have exactly " + expected + " registered session(s)")
                .atMost(Duration.ofSeconds(5))
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertThat(registry.sessionIdsFor(userId)).hasSize(expected));
    }

    @Test
    void oneUserWithTwoSocketsIsTrackedAndCleanedUpOnClose() throws Exception {
        UUID user = registerUser();

        WebSocketSession first = connect(user);
        WebSocketSession second = connect(user);
        awaitSessionCount(user, 2);

        first.close();
        awaitSessionCount(user, 1);

        second.close();
        awaitSessionCount(user, 0);
        assertThat(registry.hasEntry(user)).as("no empty entry left behind for the user").isFalse();
    }
}
