package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.client.ReferenceClient;
import com.chatplatform.client.ReferenceClient.Bug;
import com.chatplatform.client.ReferenceClient.Msg;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.message.dto.SendMessageRequest;
import com.chatplatform.realtime.websocket.ConnectionRegistry;
import com.chatplatform.realtime.websocket.WsCloseStatuses;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Targeted, deterministic tests of the reference client (ADR-0008). Each guarantee is shown in a
 * positive form (the correct client converges) and a negative form (the matching Bug flag breaks it).
 * "Converged" = the client's history for the conversation equals the PostgreSQL rows exactly
 * (id, seq, senderId, clientMsgId, body).
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
public class ReferenceClientTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "password123";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ConnectionRegistry registry;

    private final List<ReferenceClient> clients = new ArrayList<>();

    @AfterEach
    void closeClients() {
        clients.forEach(ReferenceClient::close);
        clients.clear();
    }

    private record User(UUID id, String username, String accessToken) {
    }

    private User register(String prefix) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String username = prefix + s;
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(username, username + "@example.com", PASSWORD, "U " + s), AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new User(res.getBody().user().id(), username, res.getBody().accessToken());
    }

    private HttpHeaders bearer(User u) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(u.accessToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private UUID dm(User a, User b) {
        return restTemplate.postForEntity("/api/conversations/direct",
                new HttpEntity<>(new CreateDirectRequest(b.id()), bearer(a)), ConversationResponse.class).getBody().id();
    }

    private void restSend(User u, UUID conv, String body) {
        ResponseEntity<String> r = restTemplate.postForEntity("/api/conversations/" + conv + "/messages",
                new HttpEntity<>(new SendMessageRequest(UUID.randomUUID(), body), bearer(u)), String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private ReferenceClient client(User u, Set<Bug> bugs) {
        ReferenceClient c = new ReferenceClient(u.username(), port, restTemplate, mapper, u.id(), u.username(), PASSWORD,
                42L, ReferenceClient.Config.forTests(), bugs).start();
        clients.add(c);
        await("client " + u.username() + " connected").atMost(Duration.ofSeconds(5)).until(c::isConnected);
        return c;
    }

    private List<Msg> dbRows(UUID conv) {
        return jdbcClient.sql("SELECT id, conversation_id, seq, sender_id, client_msg_id, body FROM messages "
                        + "WHERE conversation_id = :c ORDER BY seq")
                .param("c", conv)
                .query((rs, n) -> new Msg(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                        rs.getLong("seq"), rs.getObject("sender_id", UUID.class), rs.getObject("client_msg_id", UUID.class),
                        rs.getString("body")))
                .list();
    }

    private boolean converged(ReferenceClient c, UUID conv) {
        return c.history(conv).equals(dbRows(conv));
    }

    private void awaitConverged(ReferenceClient c, UUID conv) {
        await(c.name() + " converged on " + conv).atMost(Duration.ofSeconds(8)).until(() -> converged(c, conv));
    }

    /** Waits a fixed window and asserts the client did NOT converge (negative tests). */
    private void assertStaysDiverged(ReferenceClient c, UUID conv) throws InterruptedException {
        Thread.sleep(2500); // > 3 anti-entropy intervals and several ack timeouts in the test config
        assertThat(converged(c, conv)).as("the buggy client must not converge").isFalse();
    }

    // ── own messages ──

    @Test
    void ackRecordsTheClientsOwnMessageAndAdvancesContiguousSeq() {
        User a = register("rcA_");
        User b = register("rcB_");
        UUID conv = dm(a, b);
        ReferenceClient ca = client(a, Set.of(Bug.NO_ANTI_ENTROPY)); // only the ack can record it

        ca.send(conv, "my own");

        await().atMost(Duration.ofSeconds(5)).until(() -> ca.outboxSize() == 0);
        assertThat(ca.history(conv)).extracting(Msg::body).containsExactly("my own");
        assertThat(ca.contiguousSeq(conv)).isEqualTo(1);
        assertThat(converged(ca, conv)).isTrue();
    }

    // ── dedupe ──

    private ReferenceClient duplicatePushScenario(Set<Bug> bugs) throws Exception {
        User a = register("rdA_");
        User b = register("rdB_");
        UUID conv = dm(a, b);
        ReferenceClient ca = client(a, bugs);
        restSend(b, conv, "once");
        awaitConverged(ca, conv);
        Msg m = ca.history(conv).getFirst();
        String push = mapper.writeValueAsString(java.util.Map.of("v", 1, "type", "message", "message", java.util.Map.of(
                "id", m.id().toString(), "conversationId", conv.toString(), "seq", m.seq(),
                "senderId", m.senderId().toString(), "clientMsgId", m.clientMsgId().toString(), "body", m.body(),
                "createdAt", "2026-01-01T00:00:00Z")));
        ca.injectFrame(push);
        ca.injectFrame(push);
        Thread.sleep(300);
        return ca;
    }

    @Test
    void duplicatePushesAreDeduplicatedById() throws Exception {
        ReferenceClient ca = duplicatePushScenario(Set.of());
        UUID conv = ca.knownConversations().iterator().next();
        assertThat(converged(ca, conv)).isTrue();
    }

    @Test
    void withoutDedupeTheDuplicatePushCorruptsHistory() throws Exception {
        ReferenceClient ca = duplicatePushScenario(Set.of(Bug.NO_DEDUPE));
        UUID conv = ca.knownConversations().iterator().next();
        assertThat(converged(ca, conv)).as("buggy client").isFalse();
        assertThat(ca.history(conv)).hasSize(3);
    }

    // ── subscribe first ──

    private ReferenceClient commitDuringSyncScenario(Set<Bug> bugs) {
        User a = register("rsA_");
        User b = register("rsB_");
        UUID conv = dm(a, b);
        ReferenceClient ca = new ReferenceClient(a.username(), port, restTemplate, mapper, a.id(), a.username(), PASSWORD,
                42L, ReferenceClient.Config.forTests(), bugs);
        // A message is committed exactly between the client's subscribe and fetch (or fetch and subscribe).
        ca.betweenSubscribeAndFetch(() -> restSend(b, conv, "committed during sync"));
        clients.add(ca.start());
        await().atMost(Duration.ofSeconds(5)).until(ca::isConnected);
        return ca;
    }

    @Test
    void subscribeFirstThenFetchLosesNothingCommittedInBetween() {
        ReferenceClient ca = commitDuringSyncScenario(Set.of(Bug.NO_ANTI_ENTROPY));
        UUID conv = ca.knownConversations().iterator().next();
        awaitConverged(ca, conv);
        assertThat(ca.history(conv)).hasSize(1);
    }

    @Test
    void fetchBeforeSubscribeLosesTheMessageCommittedInBetween() throws Exception {
        ReferenceClient ca = commitDuringSyncScenario(EnumSet.of(Bug.FETCH_BEFORE_SUBSCRIBE, Bug.NO_ANTI_ENTROPY));
        UUID conv = ca.knownConversations().iterator().next();
        assertStaysDiverged(ca, conv);
        assertThat(ca.history(conv)).isEmpty();
    }

    // ── gap detection ──

    private ReferenceClient gapScenario(Set<Bug> bugs) {
        User a = register("rgA_");
        User b = register("rgB_");
        UUID conv = dm(a, b);
        ReferenceClient ca = client(a, bugs);
        ca.dropNextPushes(1);
        restSend(b, conv, "lost push");   // seq 1: its push is dropped
        restSend(b, conv, "next push");   // seq 2: arrives with contiguousSeq still 0
        return ca;
    }

    @Test
    void pushBeyondContiguousSeqTriggersAFetchOfTheGap() {
        ReferenceClient ca = gapScenario(Set.of(Bug.NO_ANTI_ENTROPY));
        UUID conv = ca.knownConversations().iterator().next();
        awaitConverged(ca, conv);
    }

    @Test
    void withoutGapDetectionTheHoleStays() throws Exception {
        ReferenceClient ca = gapScenario(EnumSet.of(Bug.NO_GAP_DETECTION, Bug.NO_ANTI_ENTROPY));
        UUID conv = ca.knownConversations().iterator().next();
        assertStaysDiverged(ca, conv);
        assertThat(ca.history(conv)).extracting(Msg::seq).containsExactly(2L);
    }

    // ── anti-entropy: a dropped LAST push ──

    private ReferenceClient droppedLastPushScenario(Set<Bug> bugs) {
        User a = register("raA_");
        User b = register("raB_");
        UUID conv = dm(a, b);
        ReferenceClient ca = client(a, bugs);
        ca.dropNextPushes(1);
        restSend(b, conv, "the only message"); // nothing else is ever sent: gap detection cannot fire
        return ca;
    }

    @Test
    void antiEntropyRecoversADroppedLastPushWithoutAnyForcedSync() {
        ReferenceClient ca = droppedLastPushScenario(Set.of());
        UUID conv = ca.knownConversations().iterator().next();
        awaitConverged(ca, conv); // within a few scaled intervals (0.6 s, jittered 0.5x..1.5x)
        assertThat(ca.reconnects()).as("no reconnect, no forced sync").isZero();
    }

    @Test
    void withoutAntiEntropyADroppedLastPushIsNeverRecovered() throws Exception {
        ReferenceClient ca = droppedLastPushScenario(Set.of(Bug.NO_ANTI_ENTROPY));
        UUID conv = ca.knownConversations().iterator().next();
        assertStaysDiverged(ca, conv);
    }

    // ── retries keep the clientMsgId ──

    private long rowsFrom(UUID sender, UUID conv) {
        return jdbcClient.sql("SELECT count(*) FROM messages WHERE conversation_id = :c AND sender_id = :s")
                .param("c", conv).param("s", sender).query(Long.class).single();
    }

    private ReferenceClient droppedAckScenario(User a, UUID conv, Set<Bug> bugs) {
        ReferenceClient ca = client(a, bugs);
        ca.dropNextAcks(1);
        ca.send(conv, "one intent");
        await().atMost(Duration.ofSeconds(5)).until(() -> ca.outboxSize() == 0);
        return ca;
    }

    @Test
    void ackTimeoutResendsOnTheSameSocketWithTheSameClientMsgId() {
        User a = register("rtA_");
        User b = register("rtB_");
        UUID conv = dm(a, b);
        ReferenceClient ca = droppedAckScenario(a, conv, Set.of());
        assertThat(rowsFrom(a.id(), conv)).as("one intent, one row").isEqualTo(1);
        assertThat(ca.reconnects()).as("retried on the same socket").isZero();
        awaitConverged(ca, conv);
    }

    @Test
    void newClientMsgIdOnRetryCreatesADuplicateMessage() {
        User a = register("rnA_");
        User b = register("rnB_");
        UUID conv = dm(a, b);
        droppedAckScenario(a, conv, Set.of(Bug.NEW_ID_ON_RETRY));
        assertThat(rowsFrom(a.id(), conv)).as("buggy client: two rows for one intent").isEqualTo(2);
    }

    // ── close codes ──

    @Test
    void protocolErrorStopsTheClientInsteadOfLooping() throws Exception {
        User a = register("rpA_");
        ReferenceClient ca = client(a, Set.of());
        ca.sendRaw("{\"v\":1,\"type\":\"typing\"}"); // server closes 4005
        await().atMost(Duration.ofSeconds(5)).until(() -> ca.state() == ReferenceClient.State.CLIENT_BUG);
        Thread.sleep(1000);
        assertThat(ca.reconnects()).isZero();
    }

    @Test
    void loopingOnAProtocolErrorKeepsReconnecting() throws Exception {
        User a = register("rlA_");
        ReferenceClient ca = client(a, Set.of(Bug.LOOP_ON_PROTOCOL_ERROR));
        ca.sendRaw("{\"v\":1,\"type\":\"typing\"}");
        Thread.sleep(1500);
        assertThat(ca.reconnects()).as("buggy client reconnects after its own protocol error").isPositive();
    }

    @Test
    void serverClosesWith4001_4003_4006AreFollowedByAReconnectAndTheClientStillConverges() {
        User a = register("rcxA_");
        User b = register("rcxB_");
        UUID conv = dm(a, b);
        ReferenceClient ca = client(a, Set.of());
        int expectedReconnects = 0;
        for (CloseStatus status : List.of(WsCloseStatuses.SESSION_LIFETIME_REACHED, WsCloseStatuses.SLOW_CONSUMER,
                WsCloseStatuses.IDLE_TIMEOUT)) {
            String sessionId = registry.sessionIdsFor(a.id()).iterator().next();
            registry.closeSession(a.id(), sessionId, status);
            expectedReconnects++;
            int expected = expectedReconnects;
            await("reconnect after " + status.getCode()).atMost(Duration.ofSeconds(5))
                    .until(() -> ca.reconnects() >= expected && ca.isConnected());
            restSend(b, conv, "after " + status.getCode());
        }
        awaitConverged(ca, conv);
        assertThat(ca.state()).isEqualTo(ReferenceClient.State.RUNNING);
    }

    @Test
    void credentialsRevoked4002StopsForReLogin() {
        User a = register("rrA_");
        ReferenceClient ca = client(a, Set.of());
        String sessionId = registry.sessionIdsFor(a.id()).iterator().next();
        registry.closeSession(a.id(), sessionId, WsCloseStatuses.CREDENTIALS_REVOKED);
        await().atMost(Duration.ofSeconds(5)).until(() -> ca.state() == ReferenceClient.State.LOGGED_OUT);
    }

    // ── access-token expiry: single-flight refresh ──

    @Test
    void expiredAccessTokenIsRefreshedOnceAndTheClientRecovers() {
        User a = register("reA_");
        User b = register("reB_");
        UUID conv = dm(a, b);
        ReferenceClient ca = client(a, Set.of(Bug.NO_ANTI_ENTROPY));
        ca.tamperAccessToken();

        assertThat(ca.fetchHeads()).isNotEmpty(); // 401 -> refresh -> retry
        assertThat(ca.refreshes()).isEqualTo(1);
        restSend(b, conv, "after refresh");
        awaitConverged(ca, conv);
    }

    @Test
    void concurrentRequestsWithAnExpiredTokenCauseExactlyOneRefresh() throws Exception {
        User a = register("rfA_");
        User b = register("rfB_");
        dm(a, b);
        ReferenceClient ca = client(a, Set.of(Bug.NO_ANTI_ENTROPY));
        ca.tamperAccessToken();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return ca.fetchHeads();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ca.refreshes()).as("single flight").isEqualTo(1);
        assertThat(ca.state()).isEqualTo(ReferenceClient.State.RUNNING);
    }
}
