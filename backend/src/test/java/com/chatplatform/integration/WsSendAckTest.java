package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.message.repository.MessageRepository;
import com.chatplatform.realtime.service.WsTicketService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/** Phase 2c step (b): a send frame is persisted through MessageService and acked only after commit. */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
public class WsSendAckTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcClient jdbcClient;

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

    private UUID createDm(AuthResponse a, AuthResponse b) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(a.accessToken());
        ResponseEntity<ConversationResponse> res = restTemplate.postForEntity("/api/conversations/direct",
                new HttpEntity<>(new CreateDirectRequest(b.user().id()), h), ConversationResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().id();
    }

    private WsTestClient connect(AuthResponse user) throws Exception {
        return WsTestClient.connect(port, wsTicketService.issue(user.user().id()).ticket());
    }

    private String sendFrame(UUID conversationId, UUID clientMsgId, String body) throws Exception {
        ObjectNode f = mapper.createObjectNode();
        f.put("v", 1).put("type", "send").put("conversationId", conversationId.toString());
        if (clientMsgId != null) {
            f.put("clientMsgId", clientMsgId.toString());
        }
        f.put("body", body);
        return mapper.writeValueAsString(f);
    }

    private JsonNode nextFrame(WsTestClient c) throws Exception {
        String f = c.next(5, TimeUnit.SECONDS);
        assertThat(f).as("a frame within 5 s").isNotNull();
        return mapper.readTree(f);
    }

    private long rowsInConversation(UUID conversationId) {
        return jdbcClient.sql("SELECT count(*) FROM messages WHERE conversation_id = :c")
                .param("c", conversationId).query(Long.class).single();
    }

    @Test
    void ackCarriesTheCommittedRow() throws Exception {
        AuthResponse a = register("ackA_");
        AuthResponse b = register("ackB_");
        UUID conv = createDm(a, b);
        WsTestClient c = connect(a);
        UUID cmid = UUID.randomUUID();

        c.send(sendFrame(conv, cmid, "hello"));
        JsonNode ack = nextFrame(c);

        assertThat(ack.get("type").asText()).isEqualTo("ack");
        var row = jdbcClient.sql("SELECT id, seq, created_at FROM messages WHERE sender_id = :s AND client_msg_id = :c")
                .param("s", a.user().id()).param("c", cmid)
                .query((rs, n) -> new Object[]{rs.getObject("id", UUID.class), rs.getLong("seq"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()})
                .single();
        assertThat(ack.get("messageId").asText()).isEqualTo(row[0].toString());
        assertThat(ack.get("seq").asLong()).isEqualTo((Long) row[1]);
        assertThat(ack.get("clientMsgId").asText()).isEqualTo(cmid.toString());
        assertThat(ack.get("conversationId").asText()).isEqualTo(conv.toString());
        assertThat(Instant.parse(ack.get("createdAt").asText()))
                .as("ack createdAt (Spring's ObjectMapper) equals the DB row's created_at")
                .isEqualTo(row[2]);
    }

    @Test
    void duplicateSendGetsAnIdenticalAckAndStoresOneRow() throws Exception {
        AuthResponse a = register("dupA_");
        AuthResponse b = register("dupB_");
        UUID conv = createDm(a, b);
        WsTestClient c = connect(a);
        UUID cmid = UUID.randomUUID();

        c.send(sendFrame(conv, cmid, "once"));
        JsonNode first = nextFrame(c);
        c.send(sendFrame(conv, cmid, "once"));
        JsonNode second = nextFrame(c);

        assertThat(first.get("type").asText()).isEqualTo("ack");
        assertThat(second).isEqualTo(first);
        assertThat(rowsInConversation(conv)).isEqualTo(1);
    }

    @Test
    void nonMemberAndUnknownConversationGetIdenticalErrorFramesAndNothingIsStored() throws Exception {
        AuthResponse a = register("nmA_");
        AuthResponse b = register("nmB_");
        AuthResponse outsider = register("nmX_");
        UUID conv = createDm(a, b);
        WsTestClient c = connect(outsider);

        c.send(sendFrame(conv, UUID.randomUUID(), "let me in"));
        JsonNode notMember = nextFrame(c);
        c.send(sendFrame(UUID.randomUUID(), UUID.randomUUID(), "anyone?"));
        JsonNode missing = nextFrame(c);

        assertThat(notMember.get("code").asText()).isEqualTo("CONVERSATION_NOT_FOUND");
        ((ObjectNode) notMember).remove("clientMsgId");
        ((ObjectNode) missing).remove("clientMsgId");
        assertThat(notMember).isEqualTo(missing);
        assertThat(rowsInConversation(conv)).isZero();
        assertThat(c.isOpen()).isTrue();
    }

    @Test
    void overLongBlankAndMissingClientMsgIdGetValidationErrorsAndNothingIsStored() throws Exception {
        AuthResponse a = register("valA_");
        AuthResponse b = register("valB_");
        UUID conv = createDm(a, b);
        WsTestClient c = connect(a);

        UUID longId = UUID.randomUUID();
        c.send(sendFrame(conv, longId, "x".repeat(4001)));
        JsonNode tooLong = nextFrame(c);
        assertThat(tooLong.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(tooLong.get("clientMsgId").asText()).isEqualTo(longId.toString());
        assertThat(tooLong.get("message").asText()).contains("4000");

        UUID blankId = UUID.randomUUID();
        c.send(sendFrame(conv, blankId, "   "));
        JsonNode blank = nextFrame(c);
        assertThat(blank.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(blank.get("clientMsgId").asText()).isEqualTo(blankId.toString());

        c.send(sendFrame(conv, null, "no id"));
        JsonNode noId = nextFrame(c);
        assertThat(noId.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(noId.get("clientMsgId").isNull()).isTrue();

        assertThat(rowsInConversation(conv)).as("nothing persisted").isZero();
        assertThat(c.isOpen()).isTrue();
    }

    /**
     * Ack only after commit: for one marked clientMsgId, a test-only beforeCommit hook fails the
     * transaction after the insert. The client must get INTERNAL_ERROR, no ack, and no row exists.
     */
    @Test
    void noAckWhenTheCommitFails() throws Exception {
        AuthResponse a = register("rbA_");
        AuthResponse b = register("rbB_");
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

        WsTestClient c = connect(a);
        c.send(sendFrame(conv, doomed, "never committed"));
        JsonNode reply = nextFrame(c);

        assertThat(reply.get("type").asText()).as("no ack for a rolled-back send").isEqualTo("error");
        assertThat(reply.get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(reply.get("clientMsgId").asText()).isEqualTo(doomed.toString());
        assertThat(c.next(500, TimeUnit.MILLISECONDS)).as("no further frame (no late ack)").isNull();
        assertThat(rowsInConversation(conv)).isZero();
    }
}
