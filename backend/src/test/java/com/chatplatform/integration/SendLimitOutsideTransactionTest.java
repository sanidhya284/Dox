package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.shared.ratelimit.RateLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0012 Decision 10 (amended): the send limiter is never consulted inside the send transaction, so it never
 * runs while the conversation row lock is held (Phase 4 makes it a Redis call).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SendLimitOutsideTransactionTest extends AbstractIntegrationTest {

    @SpyBean
    private RateLimits rateLimits;

    @Test
    void theSendLimitIsCheckedWithNoTransactionActive() throws Exception {
        List<Boolean> inTransaction = new CopyOnWriteArrayList<>();
        Mockito.doAnswer(inv -> {
            inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return inv.callRealMethod();
        }).when(rateLimits).checkSend(Mockito.any(), Mockito.anyBoolean());

        String s = UUID.randomUUID().toString().substring(0, 8);
        AuthResponse a = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("lt" + s, "lt" + s + "@example.com", "password123", "A"), AuthResponse.class).getBody();
        AuthResponse b = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("lu" + s, "lu" + s + "@example.com", "password123", "B"), AuthResponse.class).getBody();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(a.accessToken());
        String conv = new com.fasterxml.jackson.databind.ObjectMapper().readTree(restTemplate.exchange(
                "/api/conversations/direct", HttpMethod.POST,
                new HttpEntity<>(Map.of("otherUserId", b.user().id()), h), String.class).getBody()).get("id").asText();

        ResponseEntity<String> sent = restTemplate.exchange("/api/conversations/" + conv + "/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("clientMsgId", UUID.randomUUID(), "body", "hi"), h), String.class);
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(inTransaction).as("checkSend called once, outside any transaction").containsExactly(false);
    }
}
