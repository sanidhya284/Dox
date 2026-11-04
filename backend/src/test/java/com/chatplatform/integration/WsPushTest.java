package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.conversation.dto.CreateGroupRequest;
import com.chatplatform.message.dto.SendMessageRequest;
import com.chatplatform.message.repository.MessageRepository;
import com.chatplatform.realtime.service.WsTicketService;
import com.chatplatform.realtime.websocket.ConnectionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/** Phase 2c step (c): new messages are pushed after commit to every other open socket of the participants. */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
public class WsPushTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcClient jdbcClient;

    @SpyBean
    private ConnectionRegistry registry;

    @SpyBean
    private MessageRepository messageRepository;

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
        return h;
    }

    private UUID createDm(AuthResponse a, AuthResponse b) {
        ResponseEntity<ConversationResponse> res = restTemplate.postForEntity("/api/conversations/direct",
                new HttpEntity<>(new CreateDirectRequest(b.user().id()), bearer(a)), ConversationResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().id();
    }

    private UUID createGroup(AuthResponse owner, AuthResponse... members) {
        List<UUID> ids = new ArrayList<>();
        for (AuthResponse m : members) {
            ids.add(m.user().id());
        }
        ResponseEntity<ConversationResponse> res = restTemplate.postForEntity("/api/conversations/group",
                new HttpEntity<>(new CreateGroupRequest("g", ids), bearer(owner)), ConversationResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().id();
    }

    private WsTestClient connect(AuthResponse u) throws Exception {
        WsTestClient c = WsTestClient.connect(port, wsTicketService.issue(u.user().id()).ticket());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> registry.sessionIdsFor(u.user().id()).size() >= 1);
        return c;
    }

    private String sendFrame(UUID conversationId, UUID clientMsgId, String body) throws Exception {
        ObjectNode f = mapper.createObjectNode();
        f.put("v", 1).put("type", "send").put("conversationId", conversationId.toString())
                .put("clientMsgId", clientMsgId.toString()).put("body", body);
        return mapper.writeValueAsString(f);
    }

    /** All frames that arrive within {@code ms}, parsed. */
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

    private static long count(List<JsonNode> frames, String type) {
        return frames.stream().filter(f -> type.equals(f.get("type").asText())).count();
    }

    @Test
    void originGetsOnlyTheAckAndEveryOtherSocketGetsThePushOnce() throws Exception {
        AuthResponse a = register("puA_");
        AuthResponse b = register("puB_");
        UUID conv = createDm(a, b);
        WsTestClient s1 = connect(a);
        WsTestClient s2 = connect(a);
        WsTestClient r1 = connect(b);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> registry.sessionIdsFor(a.user().id()).size() == 2);

        UUID cmid = UUID.randomUUID();
        s1.send(sendFrame(conv, cmid, "hello all"));

        List<JsonNode> onS1 = framesWithin(s1, 1500);
        List<JsonNode> onS2 = framesWithin(s2, 500);
        List<JsonNode> onR1 = framesWithin(r1, 500);
        assertThat(count(onS1, "ack")).isEqualTo(1);
        assertThat(count(onS1, "message")).as("origin socket gets no push").isZero();
        assertThat(count(onS2, "message")).as("sender's other socket").isEqualTo(1);
        assertThat(count(onR1, "message")).as("recipient").isEqualTo(1);
        JsonNode pushed = onR1.get(0).get("message");
        assertThat(pushed.get("clientMsgId").asText()).isEqualTo(cmid.toString());
        assertThat(pushed.get("senderId").asText()).isEqualTo(a.user().id().toString());
        assertThat(pushed.get("body").asText()).isEqualTo("hello all");
    }

    @Test
    void duplicateSendIsNotPushedAgain() throws Exception {
        AuthResponse a = register("pdA_");
        AuthResponse b = register("pdB_");
        UUID conv = createDm(a, b);
        WsTestClient s1 = connect(a);
        WsTestClient r1 = connect(b);
        UUID cmid = UUID.randomUUID();

        s1.send(sendFrame(conv, cmid, "once"));
        s1.send(sendFrame(conv, cmid, "once"));

        assertThat(count(framesWithin(s1, 1500), "ack")).isEqualTo(2);
        assertThat(count(framesWithin(r1, 500), "message")).as("pushed exactly once").isEqualTo(1);
    }

    @Test
    void restSendIsPushedToEveryOpenSocketOfTheParticipants() throws Exception {
        AuthResponse a = register("prA_");
        AuthResponse b = register("prB_");
        UUID conv = createDm(a, b);
        WsTestClient s1 = connect(a);
        WsTestClient r1 = connect(b);

        ResponseEntity<String> res = restTemplate.postForEntity("/api/conversations/" + conv + "/messages",
                new HttpEntity<>(new SendMessageRequest(UUID.randomUUID(), "via rest"), bearer(a)), String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(count(framesWithin(s1, 1500), "message")).as("sender's socket (no origin socket for REST)").isEqualTo(1);
        assertThat(count(framesWithin(r1, 500), "message")).isEqualTo(1);
    }

    @Test
    void participantWhoLeftGetsNoPush() throws Exception {
        AuthResponse a = register("plA_");
        AuthResponse b = register("plB_");
        AuthResponse gone = register("plC_");
        UUID group = createGroup(a, b, gone);
        jdbcClient.sql("UPDATE conversation_participants SET left_at = now() WHERE conversation_id = :c AND user_id = :u")
                .param("c", group).param("u", gone.user().id()).update();
        WsTestClient s1 = connect(a);
        WsTestClient rb = connect(b);
        WsTestClient rGone = connect(gone);

        s1.send(sendFrame(group, UUID.randomUUID(), "who is still here?"));

        assertThat(count(framesWithin(rb, 1500), "message")).isEqualTo(1);
        assertThat(count(framesWithin(rGone, 1000), "message")).as("left_at set: no push").isZero();
    }

    @Test
    void closedRecipientDoesNotFailTheSender() throws Exception {
        AuthResponse a = register("pcA_");
        AuthResponse b = register("pcB_");
        UUID conv = createDm(a, b);
        WsTestClient s1 = connect(a);
        WsTestClient r1 = connect(b);
        r1.close();

        UUID cmid = UUID.randomUUID();
        s1.send(sendFrame(conv, cmid, "anyone there?"));
        List<JsonNode> onS1 = framesWithin(s1, 1500);
        assertThat(count(onS1, "ack")).isEqualTo(1);
        assertThat(s1.isOpen()).isTrue();
    }

    /** No push for a send whose commit fails (the push listener runs only after commit). */
    @Test
    void noPushWhenTheCommitFails() throws Exception {
        AuthResponse a = register("pfA_");
        AuthResponse b = register("pfB_");
        UUID conv = createDm(a, b);
        UUID doomed = UUID.randomUUID();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("injected failure before commit");
                }
            });
            return result;
        }).when(messageRepository).insert(any(UUID.class), anyLong(), any(UUID.class), eq(doomed), anyString());
        WsTestClient s1 = connect(a);
        WsTestClient r1 = connect(b);

        s1.send(sendFrame(conv, doomed, "never committed"));

        List<JsonNode> onS1 = framesWithin(s1, 1500);
        assertThat(count(onS1, "ack")).isZero();
        assertThat(count(onS1, "error")).isEqualTo(1);
        assertThat(count(framesWithin(r1, 1000), "message")).as("no push for a rolled-back send").isZero();
    }

    /**
     * Tomcat's per-session blocking-send timeout bounds the thread that actually writes to a stalled
     * peer. Proven, not assumed: the property is on the native session under Tomcat's own key as a
     * Long, and Tomcat's own (private) getBlockingSendTimeout() returns our value.
     */
    @Test
    void tomcatBlockingSendTimeoutIsSetAndReadByTomcat() throws Exception {
        AuthResponse a = register("ptA_");
        connect(a);
        ArgumentCaptor<WebSocketSession> captor = ArgumentCaptor.forClass(WebSocketSession.class);
        verify(registry, atLeastOnce()).register(eq(a.user().id()), captor.capture());
        Session nativeSession = ((NativeWebSocketSession) captor.getValue()).getNativeSession(Session.class);

        Object value = nativeSession.getUserProperties()
                .get(org.apache.tomcat.websocket.Constants.BLOCKING_SEND_TIMEOUT_PROPERTY);
        assertThat(value).as("user property under Tomcat's key").isInstanceOf(Long.class).isEqualTo(5000L);

        Field remoteField = org.apache.tomcat.websocket.WsSession.class.getDeclaredField("wsRemoteEndpoint");
        remoteField.setAccessible(true);
        Object remote = remoteField.get(nativeSession);
        Method getter = org.apache.tomcat.websocket.WsRemoteEndpointImplBase.class
                .getDeclaredMethod("getBlockingSendTimeout");
        getter.setAccessible(true);
        assertThat(getter.invoke(remote)).as("timeout Tomcat itself will use").isEqualTo(5000L);
    }
}
