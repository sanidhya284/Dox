package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.realtime.service.WsTicketService;
import com.chatplatform.realtime.websocket.ConnectionRegistry;
import com.chatplatform.realtime.websocket.MessagePushListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;

/**
 * A full push queue drops pushes (logged and counted; clients recover from history) and never
 * delays acks. Tiny executor: 1 thread, 1 queue slot; every push is stalled for 1 s.
 */
@TestPropertySource(properties = {"ws.push.threads=1", "ws.push.queue-capacity=1"})
@Timeout(value = 2, unit = TimeUnit.MINUTES)
public class WsPushQueueTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MessagePushListener pushListener;

    @SpyBean
    private ConnectionRegistry registry;

    private AuthResponse register(String prefix) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(prefix + s, prefix + s + "@example.com", "password123", "U " + s),
                AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    @Test
    void fullPushQueueDropsPushesButEveryAckStillArrivesPromptly() throws Exception {
        AuthResponse a = register("pqA_");
        AuthResponse b = register("pqB_");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(a.accessToken());
        UUID conv = restTemplate.postForEntity("/api/conversations/direct",
                new HttpEntity<>(new CreateDirectRequest(b.user().id()), h), ConversationResponse.class).getBody().id();
        doAnswer(invocation -> {
            Thread.sleep(1000); // a stalled push holds the only push thread
            return invocation.callRealMethod();
        }).when(registry).sendToUsers(anyCollection(), any(TextMessage.class), any());
        long droppedBefore = pushListener.droppedPushes();

        WsTestClient s1 = WsTestClient.connect(port, wsTicketService.issue(a.user().id()).ticket());
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            s1.send("{\"v\":1,\"type\":\"send\",\"conversationId\":\"" + conv + "\",\"clientMsgId\":\""
                    + UUID.randomUUID() + "\",\"body\":\"m" + i + "\"}");
        }
        int acks = 0;
        while (acks < 10) {
            String f = s1.next(5, TimeUnit.SECONDS);
            assertThat(f).as("ack %d within 5 s", acks + 1).isNotNull();
            JsonNode n = mapper.readTree(f);
            if ("ack".equals(n.get("type").asText())) {
                acks++;
            }
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(pushListener.droppedPushes() - droppedBefore).as("pushes dropped by the full queue").isGreaterThan(0);
        assertThat(ms).as("10 acks with pushes stalled 1 s each (loose)").isLessThan(5000);
    }
}
