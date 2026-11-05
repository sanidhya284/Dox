package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.conversation.repository.ParticipantRepository;
import com.chatplatform.realtime.service.WsTicketService;
import com.chatplatform.realtime.websocket.ConnectionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/** Cursor frames over WebSocket (ADR-0007): pushed after commit, only when a cursor advanced. */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
public class WsCursorTest extends AbstractIntegrationTest {

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

    @SpyBean
    private ParticipantRepository participantRepository;

    private AuthResponse register(String prefix) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(prefix + s, prefix + s + "@example.com", "password123", "U " + s),
                AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private HttpHeaders bearer(AuthResponse u) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(u.accessToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private UUID dm(AuthResponse a, AuthResponse b) {
        UUID conv = restTemplate.postForEntity("/api/conversations/direct",
                new HttpEntity<>(new CreateDirectRequest(b.user().id()), bearer(a)), ConversationResponse.class)
                .getBody().id();
        jdbcClient.sql("UPDATE conversations SET last_seq = 100 WHERE id = :c").param("c", conv).update();
        return conv;
    }

    private WsTestClient connect(AuthResponse u, int expectedSockets) throws Exception {
        WsTestClient c = WsTestClient.connect(port, wsTicketService.issue(u.user().id()).ticket());
        await().atMost(Duration.ofSeconds(5)).until(() -> registry.sessionIdsFor(u.user().id()).size() == expectedSockets);
        return c;
    }

    private String cursorFrame(UUID conv, String kind, Object seq) throws Exception {
        ObjectNode f = mapper.createObjectNode();
        f.put("v", 1).put("type", "cursor").put("conversationId", conv.toString()).put("kind", kind);
        f.set("seq", mapper.valueToTree(seq));
        return mapper.writeValueAsString(f);
    }

    private List<JsonNode> framesWithin(WsTestClient c, long ms) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        while (true) {
            long left = end - System.nanoTime();
            if (left <= 0) {
                return out;
            }
            String f = c.frames.poll(left, TimeUnit.NANOSECONDS);
            if (f != null) {
                out.add(mapper.readTree(f));
            }
        }
    }

    private static List<JsonNode> ofType(List<JsonNode> frames, String type) {
        return frames.stream().filter(f -> type.equals(f.get("type").asText())).toList();
    }

    @Test
    void advancedCursorIsPushedToEveryOtherSocketButNotTheOrigin() throws Exception {
        AuthResponse a = register("wcA_");
        AuthResponse b = register("wcB_");
        UUID conv = dm(a, b);
        WsTestClient s1 = connect(a, 1);
        WsTestClient s2 = connect(a, 2);
        WsTestClient r1 = connect(b, 1);

        s1.send(cursorFrame(conv, "read", 10));

        List<JsonNode> onR1 = ofType(framesWithin(r1, 1500), "cursor");
        List<JsonNode> onS2 = ofType(framesWithin(s2, 500), "cursor");
        List<JsonNode> onS1 = framesWithin(s1, 500);
        assertThat(onR1).hasSize(1);
        assertThat(onS2).as("the user's other tab").hasSize(1);
        assertThat(onS1).as("origin socket gets no reply on success").isEmpty();
        JsonNode c = onR1.get(0);
        assertThat(c.get("conversationId").asText()).isEqualTo(conv.toString());
        assertThat(c.get("userId").asText()).isEqualTo(a.user().id().toString());
        assertThat(c.get("lastReadSeq").asLong()).isEqualTo(10);
        assertThat(c.get("lastDeliveredSeq").asLong()).isEqualTo(10);
    }

    @Test
    void staleCursorIsNotPushed() throws Exception {
        AuthResponse a = register("wsA_");
        AuthResponse b = register("wsB_");
        UUID conv = dm(a, b);
        WsTestClient s1 = connect(a, 1);
        WsTestClient r1 = connect(b, 1);

        s1.send(cursorFrame(conv, "read", 10));
        assertThat(ofType(framesWithin(r1, 1500), "cursor")).hasSize(1);

        s1.send(cursorFrame(conv, "read", 5));
        s1.send(cursorFrame(conv, "delivered", 10));
        assertThat(framesWithin(r1, 1000)).as("nothing advanced, nothing pushed").isEmpty();
        assertThat(framesWithin(s1, 300)).isEmpty();
    }

    @Test
    void invalidCursorGetsAnErrorFrameAndBrokenJsonCloses1007() throws Exception {
        AuthResponse a = register("wiA_");
        AuthResponse b = register("wiB_");
        UUID conv = dm(a, b);
        WsTestClient s1 = connect(a, 1);

        s1.send(cursorFrame(conv, "seen", 3));
        s1.send(cursorFrame(conv, "read", -1));
        s1.send(cursorFrame(conv, "read", 101));   // beyond last_seq
        s1.send(cursorFrame(conv, "read", "ten")); // not a number
        List<JsonNode> errors = ofType(framesWithin(s1, 2000), "error");
        assertThat(errors).hasSize(4);
        for (JsonNode e : errors) {
            assertThat(e.get("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(e.get("clientMsgId").isNull()).isTrue();
        }
        assertThat(s1.isOpen()).isTrue();

        WsTestClient s2 = connect(a, 2);
        s2.send("{\"v\":1,\"type\":\"cursor\",");
        assertThat(s2.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(1007);
    }

    @Test
    void nonMemberWithHugeSeqGetsTheSameErrorAsAnUnknownConversation() throws Exception {
        AuthResponse a = register("wnA_");
        AuthResponse b = register("wnB_");
        AuthResponse outsider = register("wnX_");
        UUID conv = dm(a, b);
        WsTestClient x = connect(outsider, 1);

        x.send(cursorFrame(conv, "read", 1_000_000_000_000L));
        x.send(cursorFrame(UUID.randomUUID(), "read", 1_000_000_000_000L));
        List<JsonNode> errors = ofType(framesWithin(x, 2000), "error");

        assertThat(errors).hasSize(2);
        assertThat(errors.get(0)).isEqualTo(errors.get(1));
        assertThat(errors.get(0).get("code").asText()).isEqualTo("CONVERSATION_NOT_FOUND");
    }

    @Test
    void restCursorIsPushedToo() throws Exception {
        AuthResponse a = register("wrA_");
        AuthResponse b = register("wrB_");
        UUID conv = dm(a, b);
        WsTestClient s1 = connect(a, 1);
        WsTestClient r1 = connect(b, 1);

        ResponseEntity<String> res = restTemplate.postForEntity("/api/conversations/" + conv + "/cursor",
                new HttpEntity<>("{\"kind\":\"delivered\",\"seq\":7}", bearer(a)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(ofType(framesWithin(r1, 1500), "cursor")).hasSize(1);
        assertThat(ofType(framesWithin(s1, 500), "cursor")).as("REST has no origin socket").hasSize(1);
    }

    /** A cursor update whose commit fails is never pushed (the push listener runs after commit). */
    @Test
    void noPushWhenTheCursorCommitFails() throws Exception {
        AuthResponse a = register("wfA_");
        AuthResponse b = register("wfB_");
        UUID conv = dm(a, b);
        UUID me = a.user().id();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("injected failure before commit");
                }
            });
            return result;
        }).when(participantRepository).advanceRead(eq(conv), eq(me), anyLong());
        WsTestClient s1 = connect(a, 1);
        WsTestClient r1 = connect(b, 1);

        s1.send(cursorFrame(conv, "read", 10));

        List<JsonNode> onS1 = framesWithin(s1, 1500);
        assertThat(ofType(onS1, "error")).hasSize(1);
        assertThat(ofType(onS1, "error").get(0).get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(ofType(framesWithin(r1, 1000), "cursor")).as("no push for a rolled-back cursor").isEmpty();
        long readSeq = jdbcClient.sql("SELECT last_read_seq FROM conversation_participants WHERE conversation_id = :c AND user_id = :u")
                .param("c", conv).param("u", me).query(Long.class).single();
        assertThat(readSeq).isZero();
    }
}
