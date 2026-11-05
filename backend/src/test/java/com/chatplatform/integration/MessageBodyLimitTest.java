package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.message.dto.SendMessageRequest;
import com.chatplatform.message.service.MessageService;
import com.chatplatform.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Message bodies are capped at 4,000 characters (UTF-16 chars, as String.length() counts them).
 * Enforced in MessageService, so the REST path and the Phase 2c WebSocket path share one check.
 */
class MessageBodyLimitTest extends AbstractIntegrationTest {

    @Autowired
    private MessageService messageService;

    @Autowired
    private JdbcClient jdbcClient;

    private AuthResponse register(String prefix) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(prefix + s, prefix + s + "@example.com", "password123", "U " + s),
                AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private UUID createDm(AuthResponse a, AuthResponse b) {
        ResponseEntity<ConversationResponse> res = restTemplate.postForEntity("/api/conversations/direct",
                new HttpEntity<>(new CreateDirectRequest(b.user().id()), bearer(a.accessToken())),
                ConversationResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().id();
    }

    private ResponseEntity<String> post(AuthResponse sender, UUID conversationId, UUID clientMsgId, String body) {
        return restTemplate.postForEntity("/api/conversations/" + conversationId + "/messages",
                new HttpEntity<>(new SendMessageRequest(clientMsgId, body), bearer(sender.accessToken())),
                String.class);
    }

    private long storedCount(UUID senderId, UUID clientMsgId) {
        return jdbcClient.sql("SELECT count(*) FROM messages WHERE sender_id = :s AND client_msg_id = :c")
                .param("s", senderId).param("c", clientMsgId).query(Long.class).single();
    }

    @Test
    void restAcceptsExactly4000CharsAndRejects4001WithNothingStored() {
        AuthResponse a = register("lenA_");
        AuthResponse b = register("lenB_");
        UUID conv = createDm(a, b);

        UUID atLimit = UUID.randomUUID();
        assertThat(post(a, conv, atLimit, "x".repeat(4000)).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(storedCount(a.user().id(), atLimit)).isEqualTo(1);

        UUID overLimit = UUID.randomUUID();
        ResponseEntity<String> res = post(a, conv, overLimit, "x".repeat(4001));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("VALIDATION_ERROR");
        assertThat(storedCount(a.user().id(), overLimit)).as("nothing persisted").isZero();
    }

    @Test
    void serviceRejectsOverLongBodyEvenWithoutRequestValidation() {
        // The Phase 2c WebSocket path calls MessageService directly, with no @Valid on a REST DTO.
        AuthResponse a = register("lenS_");
        AuthResponse b = register("lenT_");
        UUID conv = createDm(a, b);
        UUID overLimit = UUID.randomUUID();

        assertThatThrownBy(() -> messageService.sendMessage(conv, a.user().id(),
                new SendMessageRequest(overLimit, "x".repeat(4001))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("4000");
        assertThat(storedCount(a.user().id(), overLimit)).as("nothing persisted").isZero();
    }
}
