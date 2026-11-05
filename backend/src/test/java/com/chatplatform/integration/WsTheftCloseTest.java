package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.auth.dto.TokenPair;
import com.chatplatform.realtime.service.WsTicketService;
import com.chatplatform.realtime.websocket.ConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Decision 5.1 B: a refresh-token replay (family revocation) closes all of that user's sockets with
 * 4002, after the revocation commits, off the request thread. Normal refresh and logout close nothing.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
public class WsTheftCloseTest extends AbstractIntegrationTest {

    /**
     * Test-only: holds back the commit of any transaction that publishes a family revocation by
     * 300 ms (beforeCommit runs after every in-transaction listener). It observes nothing; it only
     * makes "runs before commit" deterministic for the plain-@EventListener mutation check.
     */
    @TestConfiguration
    static class DelayCommitConfig {
        @Bean
        DelayCommitOnRevocation delayCommitOnRevocation() {
            return new DelayCommitOnRevocation();
        }
    }

    static class DelayCommitOnRevocation {
        @EventListener
        public void on(com.chatplatform.auth.event.RefreshTokenFamilyRevokedEvent event) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private DataSource dataSource;

    @SpyBean
    private ConnectionRegistry registry;

    /** Client side: records how the server closed the socket. */
    private static final class CloseRecorder extends TextWebSocketHandler {
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }
    }

    /** Refresh-token cookie per registered user (the token is never in a response body; ADR-0010). */
    private final java.util.Map<UUID, String> refreshTokens = new java.util.concurrent.ConcurrentHashMap<>();

    private AuthResponse registerUser(String prefix) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(prefix + s, prefix + s + "@example.com", "password123", "U " + s),
                AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        refreshTokens.put(res.getBody().user().id(), AuthCookies.refreshToken(res));
        return res.getBody();
    }

    private String rt(AuthResponse user) {
        return refreshTokens.get(user.user().id());
    }

    private CloseRecorder connect(UUID userId) throws Exception {
        CloseRecorder recorder = new CloseRecorder();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:3000");
        String ticket = wsTicketService.issue(userId).ticket();
        new StandardWebSocketClient()
                .execute(recorder, headers, URI.create("ws://localhost:" + port + "/ws?ticket=" + ticket))
                .get(5, TimeUnit.SECONDS);
        return recorder;
    }

    private void awaitRegistered(UUID userId, int count) {
        await("user " + userId + " to have " + count + " registered socket(s)")
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(registry.sessionIdsFor(userId)).hasSize(count));
    }

    private ResponseEntity<String> refresh(String refreshToken) {
        return AuthCookies.refresh(restTemplate, refreshToken);
    }

    private void awaitClosedWith4002(CloseRecorder recorder, String what) {
        await(what + " to be closed by the server with 4002")
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(recorder.closed).isDone();
                    assertThat(recorder.closed.get().getCode()).isEqualTo(4002);
                });
    }

    @Test
    void replayClosesAllOfThatUsersSocketsWith4002AndNobodyElses() throws Exception {
        AuthResponse alice = registerUser("thA_");
        AuthResponse bob = registerUser("thB_");
        UUID aliceId = alice.user().id();
        UUID bobId = bob.user().id();
        CloseRecorder alice1 = connect(aliceId);
        CloseRecorder alice2 = connect(aliceId);
        CloseRecorder bob1 = connect(bobId);
        awaitRegistered(aliceId, 2);
        awaitRegistered(bobId, 1);

        // Legitimate rotation, then the old token is replayed (theft signal).
        assertThat(refresh(rt(alice)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh(rt(alice)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        awaitClosedWith4002(alice1, "alice's first socket");
        awaitClosedWith4002(alice2, "alice's second socket");
        await("alice to be dropped from the registry")
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(registry.hasEntry(aliceId)).isFalse());
        await("bob's socket to stay open")
                .during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> !bob1.closed.isDone() && registry.sessionIdsFor(bobId).size() == 1);
    }

    @Test
    void normalRefreshAndLogoutCloseNothing() throws Exception {
        AuthResponse user = registerUser("thN_");
        UUID userId = user.user().id();
        CloseRecorder socket = connect(userId);
        awaitRegistered(userId, 1);

        ResponseEntity<String> rotated = refresh(rt(user));
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> logout = AuthCookies.logout(restTemplate, AuthCookies.refreshToken(rotated));
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Known limitation (decision 5.1): logout does not close sockets.
        await("the socket to stay open after a normal refresh and a logout")
                .during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3))
                .until(() -> !socket.closed.isDone() && registry.sessionIdsFor(userId).size() == 1);
    }

    /**
     * Inside closeAll, a separate (autocommit) connection must already see the family revoked:
     * the close is triggered only after the revocation committed.
     */
    @Test
    void socketsAreClosedOnlyAfterTheRevocationCommitted() throws Exception {
        AuthResponse user = registerUser("thC_");
        UUID userId = user.user().id();
        AtomicLong activeTokensSeenInCloseAll = new AtomicLong(-1);
        doAnswer(invocation -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL")) {
                ps.setObject(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    activeTokensSeenInCloseAll.set(rs.getLong(1));
                }
            }
            return invocation.callRealMethod();
        }).when(registry).closeAll(eq(userId), any(CloseStatus.class));

        CloseRecorder socket = connect(userId);
        awaitRegistered(userId, 1);
        assertThat(refresh(rt(user)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh(rt(user)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        awaitClosedWith4002(socket, "the socket");
        assertThat(activeTokensSeenInCloseAll.get())
                .as("active refresh tokens visible from a separate connection when closeAll ran "
                        + "(-1 = closeAll never called; > 0 = ran before the revocation committed)")
                .isZero();
    }

    /**
     * The public refresh endpoint must not run socket closes (a slow peer can stall a close).
     * Deterministic check: the thread that runs closeAll is a realtime scheduler thread, never the
     * Tomcat request thread. Response time is only a loose secondary check.
     */
    @Test
    void closeAllRunsOnTheRealtimeSchedulerNotOnTheRequestThread() throws Exception {
        AuthResponse user = registerUser("thS_");
        UUID userId = user.user().id();
        AtomicReference<String> closeAllThread = new AtomicReference<>();
        doAnswer(invocation -> {
            closeAllThread.set(Thread.currentThread().getName());
            Thread.sleep(2000); // simulate a stalled peer
            return invocation.callRealMethod();
        }).when(registry).closeAll(eq(userId), any(CloseStatus.class));

        CloseRecorder socket = connect(userId);
        awaitRegistered(userId, 1);
        assertThat(refresh(rt(user)).getStatusCode()).isEqualTo(HttpStatus.OK);

        long start = System.nanoTime();
        ResponseEntity<String> replay = refresh(rt(user));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        await("closeAll to be called").atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(closeAllThread.get()).isNotNull());
        assertThat(closeAllThread.get())
                .as("thread that ran closeAll")
                .startsWith("ws-sched-")
                .doesNotStartWith("http-nio-");
        assertThat(ms).as("loose secondary check: replay response time with closeAll stalled for 2 s")
                .isLessThan(1500);
        await("the socket to be closed eventually")
                .atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(socket.closed).isDone());
    }
}
