package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.message.dto.MessageResponse;
import com.chatplatform.message.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class MessageConcurrencyTest extends AbstractIntegrationTest {

    private String tokenA;
    private UUID userAId;
    private String tokenB;
    private UUID userBId;
    private UUID conversationId;

    @BeforeEach
    void setup() {
        // Register User A
        RegisterRequest regA = new RegisterRequest("alice_concur_" + UUID.randomUUID().toString().substring(0, 8), "alice_concur" + UUID.randomUUID() + "@example.com", "password123", "Alice");
        ResponseEntity<AuthResponse> resA = restTemplate.postForEntity("/api/auth/register", regA, AuthResponse.class);
        tokenA = resA.getBody().accessToken();
        userAId = resA.getBody().user().id();

        // Register User B
        RegisterRequest regB = new RegisterRequest("bob_concur_" + UUID.randomUUID().toString().substring(0, 8), "bob_concur" + UUID.randomUUID() + "@example.com", "password123", "Bob");
        ResponseEntity<AuthResponse> resB = restTemplate.postForEntity("/api/auth/register", regB, AuthResponse.class);
        tokenB = resB.getBody().accessToken();
        userBId = resB.getBody().user().id();

        // Create DM
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(tokenA);
        CreateDirectRequest dmReq = new CreateDirectRequest(userBId);
        ResponseEntity<ConversationResponse> dmRes = restTemplate.postForEntity("/api/conversations/direct", new HttpEntity<>(dmReq, headersA), ConversationResponse.class);
        conversationId = dmRes.getBody().id();
    }

    @Test
    void test100ConcurrentSends() {
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(tokenA);
        CreateDirectRequest dmReq = new CreateDirectRequest(userBId);
        ResponseEntity<ConversationResponse> dmRes = restTemplate.postForEntity("/api/conversations/direct", new HttpEntity<>(dmReq, headersA), ConversationResponse.class);
        UUID freshConvId = dmRes.getBody().id();

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<CompletableFuture<ResponseEntity<MessageResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            CompletableFuture<ResponseEntity<MessageResponse>> future = CompletableFuture.supplyAsync(() -> {
                SendMessageRequest req = new SendMessageRequest(UUID.randomUUID(), "Concurrent msg " + index);
                return restTemplate.postForEntity(
                        "/api/conversations/" + freshConvId + "/messages",
                        new HttpEntity<>(req, headersA),
                        MessageResponse.class
                );
            }, executor);
            futures.add(future);
        }

        // Wait for all
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Set<Long> seqs = ConcurrentHashMap.newKeySet();
        List<MessageResponse> responses = new ArrayList<>();
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;

        for (var f : futures) {
            ResponseEntity<MessageResponse> responseEntity = f.join();
            assertThat(responseEntity.getStatusCode().is2xxSuccessful()).isTrue();
            MessageResponse msg = responseEntity.getBody();
            assertThat(msg).isNotNull();
            seqs.add(msg.seq());
            responses.add(msg);
            if (msg.seq() < min) min = msg.seq();
            if (msg.seq() > max) max = msg.seq();
        }

        assertThat(seqs).hasSize(threadCount);
        assertThat(max - min + 1).isEqualTo(threadCount);
    }

    @Test
    void testIdempotencyUnderFire() {
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(tokenA);
        CreateDirectRequest dmReq = new CreateDirectRequest(userBId);
        ResponseEntity<ConversationResponse> dmRes = restTemplate.postForEntity("/api/conversations/direct", new HttpEntity<>(dmReq, headersA), ConversationResponse.class);
        UUID freshConvId = dmRes.getBody().id();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<CompletableFuture<ResponseEntity<MessageResponse>>> futures = new ArrayList<>();

        // Same client msg ID for all requests
        UUID clientMsgId = UUID.randomUUID();

        for (int i = 0; i < threadCount; i++) {
            CompletableFuture<ResponseEntity<MessageResponse>> future = CompletableFuture.supplyAsync(() -> {
                SendMessageRequest req = new SendMessageRequest(clientMsgId, "Idempotent msg");
                return restTemplate.postForEntity(
                        "/api/conversations/" + freshConvId + "/messages",
                        new HttpEntity<>(req, headersA),
                        MessageResponse.class
                );
            }, executor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long assignedSeq = -1;
        UUID assignedId = null;

        for (var f : futures) {
            ResponseEntity<MessageResponse> responseEntity = f.join();
            assertThat(responseEntity.getStatusCode().is2xxSuccessful()).isTrue();
            MessageResponse msg = responseEntity.getBody();
            assertThat(msg).isNotNull();
            
            if (assignedSeq == -1) {
                assignedSeq = msg.seq();
                assignedId = msg.id();
            } else {
                assertThat(msg.seq()).isEqualTo(assignedSeq);
                assertThat(msg.id()).isEqualTo(assignedId);
            }
        }
    }

    @Test
    void duplicateConcurrentSendsLeaveNoSequenceGap() {
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(tokenA);
        CreateDirectRequest dmReq = new CreateDirectRequest(userBId);
        ResponseEntity<ConversationResponse> dmRes = restTemplate.postForEntity("/api/conversations/direct", new HttpEntity<>(dmReq, headersA), ConversationResponse.class);
        UUID freshConvId = dmRes.getBody().id();

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<CompletableFuture<ResponseEntity<MessageResponse>>> futures = new ArrayList<>();
        UUID clientMsgId = UUID.randomUUID();

        for (int i = 0; i < threadCount; i++) {
            CompletableFuture<ResponseEntity<MessageResponse>> future = CompletableFuture.supplyAsync(() -> {
                SendMessageRequest req = new SendMessageRequest(clientMsgId, "Concurrent duplicate body");
                return restTemplate.postForEntity(
                        "/api/conversations/" + freshConvId + "/messages",
                        new HttpEntity<>(req, headersA),
                        MessageResponse.class
                );
            }, executor);
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long firstSeq = -1;
        UUID firstId = null;
        for (var f : futures) {
            ResponseEntity<MessageResponse> responseEntity = f.join();
            assertThat(responseEntity.getStatusCode().is2xxSuccessful()).isTrue();
            MessageResponse msg = responseEntity.getBody();
            if (firstSeq == -1) {
                firstSeq = msg.seq();
                firstId = msg.id();
            } else {
                assertThat(msg.id()).isEqualTo(firstId);
                assertThat(msg.seq()).isEqualTo(firstSeq);
            }
        }

        // Now send 1 further distinct message
        SendMessageRequest nextReq = new SendMessageRequest(UUID.randomUUID(), "Next distinct message");
        ResponseEntity<MessageResponse> nextRes = restTemplate.postForEntity(
                "/api/conversations/" + freshConvId + "/messages",
                new HttpEntity<>(nextReq, headersA),
                MessageResponse.class
        );
        assertThat(nextRes.getStatusCode().is2xxSuccessful()).isTrue();
        
        long nextSeq = nextRes.getBody().seq();
        assertThat(nextSeq).isEqualTo(firstSeq + 1);
    }
}
