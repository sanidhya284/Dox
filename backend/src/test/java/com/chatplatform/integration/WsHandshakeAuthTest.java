package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.realtime.dto.IssuedTicket;
import com.chatplatform.realtime.service.WsTicketService;
import com.chatplatform.realtime.websocket.ConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for Phase 2b-i: WebSocket handshake authentication and origin verification.
 */
public class WsHandshakeAuthTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private ConnectionRegistry connectionRegistry;

    @Autowired
    private JdbcClient jdbcClient;

    private UUID registerAndGetId(String suffix) {
        RegisterRequest req = new RegisterRequest(
                "ws_user_" + suffix,
                "ws_" + suffix + "@example.com",
                "password123",
                "WS User " + suffix
        );
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register", req, AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNotNull();
        return res.getBody().user().id();
    }

    private WebSocketSession connect(String ticket, String origin) throws Exception {
        return connectAsync(ticket, origin).get(5, TimeUnit.SECONDS);
    }

    private CompletableFuture<WebSocketSession> connectAsync(String ticket, String origin) {
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (origin != null) {
            headers.setOrigin(origin);
        }
        String url = "ws://localhost:" + port + "/ws" + (ticket != null ? "?ticket=" + ticket : "");
        return client.execute(new TextWebSocketHandler(), headers, URI.create(url));
    }

    private void assertHandshakeFails(String ticket, String origin) {
        long start = System.nanoTime();
        assertThatThrownBy(() -> connect(ticket, origin))
                .isInstanceOf(ExecutionException.class)
                // Every rejection is a 401 from our interceptor (not e.g. a 403 from Spring's own origin check).
                .rootCause()
                .hasMessageContaining("[401]");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMs)
                .as("Handshake rejection must fail promptly, not hang until timeout")
                .isLessThan(2000);
    }

    /**
     * Waits until the server has registered exactly one open session for {@code expectedUserId}.
     *
     * <p>The registry is keyed by the user id the handshake interceptor resolved from the ticket, so
     * this proves the authenticated user is the one bound to the socket (fresh users per test, so one
     * session is exact). connect() returns when the client has the 101 response, but the server
     * registers in afterConnectionEstablished after writing it, so this polls instead of reading once.
     */
    private void awaitSessionFor(UUID expectedUserId) {
        await("exactly one registered session for user " + expectedUserId)
                .atMost(Duration.ofSeconds(5))
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertThat(connectionRegistry.sessionIdsFor(expectedUserId))
                        .as("sessions registered for user %s", expectedUserId)
                        .hasSize(1));
    }

    private String createExpiredTicket(UUID userId) {
        IssuedTicket ticket = wsTicketService.issue(userId);
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        jdbcClient.sql("UPDATE ws_tickets SET expires_at = :past WHERE user_id = :userId")
                .param("past", past)
                .param("userId", userId)
                .update();
        return ticket.ticket();
    }

    @Test
    void validTicketWithAllowedOriginSuccessfullyUpgradesHandshake() throws Exception {
        UUID userId = registerAndGetId(UUID.randomUUID().toString().substring(0, 8));
        IssuedTicket ticket = wsTicketService.issue(userId);

        WebSocketSession session = connect(ticket.ticket(), "http://localhost:3000");
        try {
            assertThat(session.isOpen()).isTrue();
            awaitSessionFor(userId);
        } finally {
            session.close();
        }
    }

    @Test
    void validTicketWithSecondAllowedOriginSuccessfullyUpgradesHandshake() throws Exception {
        UUID userId = registerAndGetId(UUID.randomUUID().toString().substring(0, 8));
        IssuedTicket ticket = wsTicketService.issue(userId);

        WebSocketSession session = connect(ticket.ticket(), "http://localhost:5173");
        try {
            assertThat(session.isOpen()).isTrue();
            awaitSessionFor(userId);
        } finally {
            session.close();
        }
    }

    @Test
    void missingTicketParameterFailsHandshake() {
        assertHandshakeFails(null, "http://localhost:3000");
    }

    @Test
    void blankTicketParameterFailsHandshake() {
        assertHandshakeFails("", "http://localhost:3000");
    }

    @Test
    void unknownTicketFailsHandshake() {
        String randomTicket = Base64.getUrlEncoder().withoutPadding().encodeToString("nonexistent-random-ticket-32-bytes!".getBytes(StandardCharsets.UTF_8));
        assertHandshakeFails(randomTicket, "http://localhost:3000");
    }

    @Test
    void expiredTicketFailsHandshake() {
        UUID userId = registerAndGetId(UUID.randomUUID().toString().substring(0, 8));
        String expiredTicket = createExpiredTicket(userId);

        assertHandshakeFails(expiredTicket, "http://localhost:3000");
    }

    @Test
    void ticketCanOnlyBeUsedOnceForHandshake() throws Exception {
        UUID userId = registerAndGetId(UUID.randomUUID().toString().substring(0, 8));
        IssuedTicket ticket = wsTicketService.issue(userId);

        // First attempt with allowed origin succeeds
        WebSocketSession session = connect(ticket.ticket(), "http://localhost:3000");
        assertThat(session.isOpen()).isTrue();
        session.close();

        // Second attempt with the EXACT SAME ticket and the EXACT SAME origin fails
        assertHandshakeFails(ticket.ticket(), "http://localhost:3000");
    }

    @Test
    void disallowedOriginFailsHandshakeAndLeavesTicketUnconsumed() throws Exception {
        UUID userId = registerAndGetId(UUID.randomUUID().toString().substring(0, 8));
        IssuedTicket ticket = wsTicketService.issue(userId);

        // Attempt from disallowed origin fails
        assertHandshakeFails(ticket.ticket(), "https://malicious-site.com");

        // Subsequent retry with allowed origin succeeds because origin check was before ticket consumption
        WebSocketSession session = connect(ticket.ticket(), "http://localhost:3000");
        try {
            assertThat(session.isOpen()).isTrue();
            awaitSessionFor(userId);
        } finally {
            session.close();
        }
    }

    @Test
    void missingOriginHeaderFailsHandshakeAndLeavesTicketUnconsumed() throws Exception {
        UUID userId = registerAndGetId(UUID.randomUUID().toString().substring(0, 8));
        IssuedTicket ticket = wsTicketService.issue(userId);

        // Attempt with no Origin header fails
        assertHandshakeFails(ticket.ticket(), null);

        // Subsequent retry with allowed origin succeeds
        WebSocketSession session = connect(ticket.ticket(), "http://localhost:3000");
        try {
            assertThat(session.isOpen()).isTrue();
            awaitSessionFor(userId);
        } finally {
            session.close();
        }
    }

    @Test
    void twoValidTicketsForDifferentUsersBothSucceedIndependently() throws Exception {
        UUID user1Id = registerAndGetId("u1_" + UUID.randomUUID().toString().substring(0, 8));
        UUID user2Id = registerAndGetId("u2_" + UUID.randomUUID().toString().substring(0, 8));
        IssuedTicket ticket1 = wsTicketService.issue(user1Id);
        IssuedTicket ticket2 = wsTicketService.issue(user2Id);

        WebSocketSession session1 = connect(ticket1.ticket(), "http://localhost:3000");
        WebSocketSession session2 = connect(ticket2.ticket(), "http://localhost:3000");

        try {
            assertThat(session1.isOpen()).isTrue();
            assertThat(session2.isOpen()).isTrue();

            awaitSessionFor(user1Id);
            awaitSessionFor(user2Id);
            assertThat(connectionRegistry.sessionIdsFor(user1Id))
                    .doesNotContainAnyElementsOf(connectionRegistry.sessionIdsFor(user2Id));
        } finally {
            session1.close();
            session2.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:3000.evil.com",
            "http://localhost:3000/",
            "http://localhost:3001",
            "https://localhost:3000",
            "HTTP://LOCALHOST:3000"
    })
    void nearMissOriginFailsHandshakeAndLeavesTicketUnconsumed(String nearMiss) throws Exception {
        UUID userId = registerAndGetId(UUID.randomUUID().toString().substring(0, 8));
        IssuedTicket ticket = wsTicketService.issue(userId);

        assertHandshakeFails(ticket.ticket(), nearMiss);

        WebSocketSession session = connect(ticket.ticket(), "http://localhost:3000");
        try {
            assertThat(session.isOpen()).isTrue();
            awaitSessionFor(userId);
        } finally {
            session.close();
        }
    }

    @Test
    void pathsUnderWsAreNotPublic() {
        // Only the exact /ws endpoint bypasses JWT auth; anything below it must require a token.
        ResponseEntity<String> res = restTemplate.getForEntity("/ws/anything", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
