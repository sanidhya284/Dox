package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.realtime.dto.IssuedTicket;
import com.chatplatform.realtime.service.WsTicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WsTicketTest extends AbstractIntegrationTest {

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private JdbcClient jdbcClient;

    // ── Helpers ────────────────────────────────────────────────────────────────

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

    private String registerAndGetToken(String suffix) {
        RegisterRequest req = new RegisterRequest(
                "ws_user_" + suffix,
                "ws_" + suffix + "@example.com",
                "password123",
                "WS User " + suffix
        );
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register", req, AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNotNull();
        return res.getBody().accessToken();
    }

    private String sha256Hex(String raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void issuedTicketConsumesToTheRightUserExactlyOnce() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID userId = registerAndGetId(s);

        IssuedTicket ticket = wsTicketService.issue(userId);
        assertThat(ticket.ticket()).isNotBlank();
        assertThat(ticket.expiresInSeconds()).isEqualTo(30);

        Optional<UUID> first = wsTicketService.consume(ticket.ticket());
        assertThat(first).isPresent();
        assertThat(first.get()).isEqualTo(userId);

        Optional<UUID> second = wsTicketService.consume(ticket.ticket());
        assertThat(second).isEmpty();
    }

    @Test
    void unknownTicketReturnsEmpty() {
        Optional<UUID> result = wsTicketService.consume("not-a-ticket");
        assertThat(result).isEmpty();
    }

    @Test
    void expiredTicketIsRejected() throws Exception {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID userId = registerAndGetId(s);

        String rawTicket = "expired-raw-ticket-" + s;
        String hash = sha256Hex(rawTicket);

        Instant past = Instant.now().minusSeconds(60);
        jdbcClient.sql("INSERT INTO ws_tickets (ticket_hash, user_id, expires_at) VALUES (:hash, :userId, :expiresAt)")
                .param("hash", hash)
                .param("userId", userId)
                .param("expiresAt", OffsetDateTime.ofInstant(past, ZoneOffset.UTC))
                .update();

        Optional<UUID> result = wsTicketService.consume(rawTicket);
        assertThat(result).isEmpty();
    }

    @Test
    void concurrentConsumeOfOneTicketYieldsExactlyOneSuccess() throws Exception {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID userId = registerAndGetId(s);

        IssuedTicket ticket = wsTicketService.issue(userId);

        int numThreads = 20;
        // ready: each worker counts down when it is parked at the start gate
        CountDownLatch ready = new CountDownLatch(numThreads);
        // start: released by main thread once all workers are ready
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<Optional<UUID>>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate was not opened within 10 s");
                }
                return wsTicketService.consume(ticket.ticket());
            }));
        }

        // Wait until all 20 workers are parked, then release them simultaneously
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int successCount = 0;
        int emptyCount = 0;
        try {
            for (Future<Optional<UUID>> f : futures) {
                Optional<UUID> result = f.get(30, TimeUnit.SECONDS);
                if (result.isPresent()) {
                    assertThat(result.get()).isEqualTo(userId);
                    successCount++;
                } else {
                    emptyCount++;
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(successCount).isEqualTo(1);
        assertThat(emptyCount).isEqualTo(19);
    }

    @Test
    void ticketIsStoredOnlyAsHash() throws Exception {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID userId = registerAndGetId(s);

        IssuedTicket ticket = wsTicketService.issue(userId);
        String rawTicket = ticket.ticket();

        String storedHash = jdbcClient
                .sql("SELECT ticket_hash FROM ws_tickets WHERE user_id = :userId ORDER BY created_at DESC LIMIT 1")
                .param("userId", userId)
                .query(String.class)
                .single();

        assertThat(storedHash).isNotEqualTo(rawTicket);
        assertThat(storedHash).isEqualTo(sha256Hex(rawTicket));
    }

    @Test
    void issuingCleansUpExpiredTickets() throws Exception {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID userId = registerAndGetId(s);

        // Insert an expired row
        String expiredHash = sha256Hex("expired-for-cleanup-" + s);
        Instant past = Instant.now().minusSeconds(60);
        jdbcClient.sql("INSERT INTO ws_tickets (ticket_hash, user_id, expires_at) VALUES (:hash, :userId, :expiresAt)")
                .param("hash", expiredHash)
                .param("userId", userId)
                .param("expiresAt", OffsetDateTime.ofInstant(past, ZoneOffset.UTC))
                .update();

        // Verify expired row exists
        Integer countBefore = jdbcClient
                .sql("SELECT COUNT(*) FROM ws_tickets WHERE ticket_hash = :hash")
                .param("hash", expiredHash)
                .query(Integer.class)
                .single();
        assertThat(countBefore).isEqualTo(1);

        // Issue a new ticket — this should clean up expired rows
        IssuedTicket newTicket = wsTicketService.issue(userId);

        // Expired row should be gone
        Integer countAfter = jdbcClient
                .sql("SELECT COUNT(*) FROM ws_tickets WHERE ticket_hash = :hash")
                .param("hash", expiredHash)
                .query(Integer.class)
                .single();
        assertThat(countAfter).isEqualTo(0);

        // New ticket should exist
        Integer newCount = jdbcClient
                .sql("SELECT COUNT(*) FROM ws_tickets WHERE user_id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single();
        assertThat(newCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void twoTicketsForOneUserAreIndependent() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID userId = registerAndGetId(s);

        IssuedTicket t1 = wsTicketService.issue(userId);
        IssuedTicket t2 = wsTicketService.issue(userId);

        assertThat(t1.ticket()).isNotEqualTo(t2.ticket());

        Optional<UUID> r1 = wsTicketService.consume(t1.ticket());
        assertThat(r1).isPresent();
        assertThat(r1.get()).isEqualTo(userId);

        Optional<UUID> r2 = wsTicketService.consume(t2.ticket());
        assertThat(r2).isPresent();
        assertThat(r2.get()).isEqualTo(userId);

        // Both are now consumed
        assertThat(wsTicketService.consume(t1.ticket())).isEmpty();
        assertThat(wsTicketService.consume(t2.ticket())).isEmpty();
    }

    @Test
    void ticketsAreRandomAndLongEnough() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID userId = registerAndGetId(s);

        Set<String> tickets = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            IssuedTicket t = wsTicketService.issue(userId);
            tickets.add(t.ticket());

            // Decode from base64url (no padding) — must decode to exactly 32 bytes
            byte[] bytes = Base64.getUrlDecoder().decode(t.ticket());
            assertThat(bytes).hasSize(32);
        }

        // All 50 are distinct
        assertThat(tickets).hasSize(50);
    }

    @Test
    void issueEndpointRequiresAuthAndReturnsTicket() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String token = registerAndGetToken("ep_" + s);

        // POST without token -> 401
        ResponseEntity<Map> noAuth = restTemplate.postForEntity("/api/ws/ticket", null, Map.class);
        assertThat(noAuth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // POST with valid token -> 200 with ticket
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map> withAuth = restTemplate.postForEntity(
                "/api/ws/ticket",
                new HttpEntity<>(null, headers),
                Map.class
        );
        assertThat(withAuth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(withAuth.getBody()).isNotNull();
        assertThat(withAuth.getBody().get("ticket")).isNotNull();
        assertThat((String) withAuth.getBody().get("ticket")).isNotBlank();
        assertThat(withAuth.getBody().get("expiresInSeconds")).isEqualTo(30);

        // Consume via the service -> returns the caller's id
        String rawTicket = (String) withAuth.getBody().get("ticket");
        Optional<UUID> consumed = wsTicketService.consume(rawTicket);
        assertThat(consumed).isPresent();
    }
}
