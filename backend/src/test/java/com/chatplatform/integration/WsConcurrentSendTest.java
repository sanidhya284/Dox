package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateGroupRequest;
import com.chatplatform.realtime.service.WsTicketService;
import com.chatplatform.realtime.websocket.ConnectionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
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
 * 3 members x 2 sockets, every socket sending 20 messages concurrently into one group (120 in all).
 * Seq must be exactly 1..120, and every socket must receive exactly its own 20 acks and exactly the
 * other 100 messages as pushes, each once (matched by messageId).
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
public class WsConcurrentSendTest extends AbstractIntegrationTest {

    private static final int SOCKETS_PER_USER = 2;
    private static final int PER_SOCKET = 20;

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ConnectionRegistry registry;

    private AuthResponse register(String prefix) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(prefix + s, prefix + s + "@example.com", "password123", "U " + s),
                AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private record Sock(WsTestClient client, Set<UUID> ownClientMsgIds) {
    }

    @Test
    void concurrentSendsGiveContiguousSeqAndEverySocketGetsEachMessageExactlyOnce() throws Exception {
        AuthResponse a = register("ccA_");
        AuthResponse b = register("ccB_");
        AuthResponse c = register("ccC_");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(a.accessToken());
        UUID group = restTemplate.postForEntity("/api/conversations/group",
                new HttpEntity<>(new CreateGroupRequest("cc", List.of(b.user().id(), c.user().id())), h),
                ConversationResponse.class).getBody().id();

        List<Sock> sockets = new ArrayList<>();
        for (AuthResponse u : List.of(a, b, c)) {
            for (int i = 0; i < SOCKETS_PER_USER; i++) {
                sockets.add(new Sock(WsTestClient.connect(port, wsTicketService.issue(u.user().id()).ticket()),
                        new HashSet<>()));
            }
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> registry.sessionIdsFor(u.user().id()).size() == SOCKETS_PER_USER);
        }
        int total = sockets.size() * PER_SOCKET;

        ExecutorService pool = Executors.newFixedThreadPool(sockets.size());
        CountDownLatch ready = new CountDownLatch(sockets.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Sock s : sockets) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < PER_SOCKET; i++) {
                        UUID cmid = UUID.randomUUID();
                        synchronized (s.ownClientMsgIds()) {
                            s.ownClientMsgIds().add(cmid);
                        }
                        s.client().send("{\"v\":1,\"type\":\"send\",\"conversationId\":\"" + group
                                + "\",\"clientMsgId\":\"" + cmid + "\",\"body\":\"m" + i + "\"}");
                    }
                    return null;
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

        // Every socket should end with exactly PER_SOCKET acks + (total - PER_SOCKET) pushes.
        int expectedFrames = total;
        await("every socket to receive " + expectedFrames + " frames")
                .atMost(Duration.ofSeconds(30))
                .until(() -> sockets.stream().allMatch(s -> s.client().frames.size() >= expectedFrames));
        Thread.sleep(1000); // and nothing more arrives

        List<UUID> allIds = jdbcClient.sql("SELECT id FROM messages WHERE conversation_id = :c")
                .param("c", group).query(UUID.class).list();
        List<Long> seqs = jdbcClient.sql("SELECT seq FROM messages WHERE conversation_id = :c ORDER BY seq")
                .param("c", group).query(Long.class).list();
        assertThat(allIds).hasSize(total);
        List<Long> expectedSeqs = new ArrayList<>();
        for (long i = 1; i <= total; i++) {
            expectedSeqs.add(i);
        }
        assertThat(seqs).as("contiguous seq").isEqualTo(expectedSeqs);

        for (Sock s : sockets) {
            List<JsonNode> frames = new ArrayList<>();
            for (String f : s.client().frames) {
                frames.add(mapper.readTree(f));
            }
            Set<UUID> ackedOwn = new HashSet<>();
            List<UUID> ackIds = new ArrayList<>();
            List<UUID> pushedIds = new ArrayList<>();
            for (JsonNode f : frames) {
                switch (f.get("type").asText()) {
                    case "ack" -> {
                        ackIds.add(UUID.fromString(f.get("messageId").asText()));
                        ackedOwn.add(UUID.fromString(f.get("clientMsgId").asText()));
                    }
                    case "message" -> pushedIds.add(UUID.fromString(f.get("message").get("id").asText()));
                    default -> throw new AssertionError("unexpected frame " + f);
                }
            }
            assertThat(frames).as("frames on one socket").hasSize(total);
            assertThat(ackIds).as("own acks").hasSize(PER_SOCKET).doesNotHaveDuplicates();
            assertThat(ackedOwn).as("acks match own clientMsgIds").isEqualTo(s.ownClientMsgIds());
            assertThat(pushedIds).as("pushes").hasSize(total - PER_SOCKET).doesNotHaveDuplicates();
            Set<UUID> union = new HashSet<>(ackIds);
            union.addAll(pushedIds);
            assertThat(union).as("acks + pushes cover every message exactly once").containsExactlyInAnyOrderElementsOf(allIds);
        }
    }
}
