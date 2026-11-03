package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.message.dto.MessageResponse;
import com.chatplatform.message.dto.SendMessageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class MessageThroughputTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void fiveThousandConcurrentSendsProduceExactlyContiguousSequence() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        RegisterRequest regA = new RegisterRequest(
                "alice_tp_" + suffix,
                "alice_tp_" + suffix + "@example.com",
                "password123",
                "Alice"
        );
        ResponseEntity<AuthResponse> resA = restTemplate.postForEntity("/api/auth/register", regA, AuthResponse.class);
        assertThat(resA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resA.getBody()).isNotNull();
        String tokenA = resA.getBody().accessToken();

        RegisterRequest regB = new RegisterRequest(
                "bob_tp_" + suffix,
                "bob_tp_" + suffix + "@example.com",
                "password123",
                "Bob"
        );
        ResponseEntity<AuthResponse> resB = restTemplate.postForEntity("/api/auth/register", regB, AuthResponse.class);
        assertThat(resB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resB.getBody()).isNotNull();
        UUID userBId = resB.getBody().user().id();

        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(tokenA);
        CreateDirectRequest dmReq = new CreateDirectRequest(userBId);
        ResponseEntity<ConversationResponse> dmRes = restTemplate.postForEntity(
                "/api/conversations/direct",
                new HttpEntity<>(dmReq, headersA),
                ConversationResponse.class
        );
        assertThat(dmRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(dmRes.getBody()).isNotNull();
        UUID conversationId = dmRes.getBody().id();

        int numWorkers = 100;
        int msgsPerWorker = 50;
        int totalMessages = numWorkers * msgsPerWorker;

        ExecutorService executor = Executors.newFixedThreadPool(numWorkers);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<List<MessageResponse>> workerResponses = new ArrayList<>(numWorkers);
        for (int i = 0; i < numWorkers; i++) {
            workerResponses.add(new ArrayList<>(msgsPerWorker));
        }

        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>(totalMessages));
        List<Future<?>> futures = new ArrayList<>(numWorkers);

        for (int w = 0; w < numWorkers; w++) {
            final int workerIndex = w;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    List<MessageResponse> list = new ArrayList<>(msgsPerWorker);
                    for (int m = 0; m < msgsPerWorker; m++) {
                        SendMessageRequest req = new SendMessageRequest(UUID.randomUUID(), "Msg w" + workerIndex + " m" + m);
                        ResponseEntity<MessageResponse> res = restTemplate.postForEntity(
                                "/api/conversations/" + conversationId + "/messages",
                                new HttpEntity<>(req, headersA),
                                MessageResponse.class
                        );
                        statusCodes.add(res.getStatusCode().value());
                        if (res.getBody() != null) {
                            list.add(res.getBody());
                        }
                    }
                    synchronized (workerResponses) {
                        workerResponses.set(workerIndex, list);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown();

        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        long elapsedMs = System.currentTimeMillis() - startTime;
        double msgsPerSec = elapsedMs > 0 ? (totalMessages * 1000.0 / elapsedMs) : 0;
        System.out.println(String.format("THROUGHPUT 5000 msgs in %d ms (%.1f msgs/sec)", elapsedMs, msgsPerSec));

        // Assert: all 5000 responses are 201
        assertThat(statusCodes).hasSize(totalMessages);
        for (int code : statusCodes) {
            assertThat(code).isEqualTo(201);
        }

        // Assert: each worker's own 50 seqs are strictly increasing in send order
        Set<Long> allSeqs = new HashSet<>(totalMessages);
        for (int w = 0; w < numWorkers; w++) {
            List<MessageResponse> workerMsgs = workerResponses.get(w);
            assertThat(workerMsgs).hasSize(msgsPerWorker);

            for (int m = 1; m < msgsPerWorker; m++) {
                assertThat(workerMsgs.get(m).seq())
                        .isGreaterThan(workerMsgs.get(m - 1).seq());
            }

            for (MessageResponse msg : workerMsgs) {
                allSeqs.add(msg.seq());
            }
        }

        // Assert: the set of returned seqs equals exactly {1..5000}
        Set<Long> expectedSeqs = LongStream.rangeClosed(1, totalMessages).boxed().collect(Collectors.toSet());
        assertThat(allSeqs).isEqualTo(expectedSeqs);

        // Assert: a direct SQL count of messages for that conversation is 5000
        Integer dbCount = jdbcClient.sql("SELECT count(*) FROM messages WHERE conversation_id = ?")
                .param(conversationId)
                .query(Integer.class)
                .single();
        assertThat(dbCount).isEqualTo(totalMessages);
    }
}
