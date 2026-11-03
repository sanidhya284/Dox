package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.message.dto.MessagePage;
import com.chatplatform.message.dto.MessageResponse;
import com.chatplatform.message.dto.SendMessageRequest;
import com.chatplatform.message.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SeqRollbackTest extends AbstractIntegrationTest {

    @SpyBean
    private MessageRepository messageRepository;

    private AuthResponse register(String suffix) {
        RegisterRequest req = new RegisterRequest(
                "user_" + suffix,
                "user_" + suffix + "@example.com",
                "password123",
                "User " + suffix
        );
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register", req, AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @Test
    void sendFailureAfterSeqAllocationLeavesNoGap() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse a = register("sq_a_" + s);
        AuthResponse b = register("sq_b_" + s);

        CreateDirectRequest dmReq = new CreateDirectRequest(b.user().id());
        ResponseEntity<ConversationResponse> dmRes = restTemplate.postForEntity(
                "/api/conversations/direct",
                new HttpEntity<>(dmReq, bearer(a.accessToken())),
                ConversationResponse.class
        );
        assertThat(dmRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(dmRes.getBody()).isNotNull();
        UUID conversationId = dmRes.getBody().id();

        // Send 2 messages (seq 1 and 2)
        SendMessageRequest req1 = new SendMessageRequest(UUID.randomUUID(), "msg 1");
        ResponseEntity<MessageResponse> res1 = restTemplate.postForEntity(
                "/api/conversations/" + conversationId + "/messages",
                new HttpEntity<>(req1, bearer(a.accessToken())),
                MessageResponse.class
        );
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res1.getBody()).isNotNull();
        assertThat(res1.getBody().seq()).isEqualTo(1L);

        SendMessageRequest req2 = new SendMessageRequest(UUID.randomUUID(), "msg 2");
        ResponseEntity<MessageResponse> res2 = restTemplate.postForEntity(
                "/api/conversations/" + conversationId + "/messages",
                new HttpEntity<>(req2, bearer(a.accessToken())),
                MessageResponse.class
        );
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res2.getBody()).isNotNull();
        assertThat(res2.getBody().seq()).isEqualTo(2L);

        // Reset invocation history on the spy so the next call count starts at 0
        Mockito.clearInvocations(messageRepository);

        // Stub the spy so the NEXT call to insert(...) throws RuntimeException and later calls run the real method
        doThrow(new RuntimeException("boom")).doCallRealMethod()
                .when(messageRepository).insert(any(), anyLong(), any(), any(), any());

        // Send a third message: expect 500
        SendMessageRequest req3 = new SendMessageRequest(UUID.randomUUID(), "msg 3");
        ResponseEntity<Object> res3 = restTemplate.postForEntity(
                "/api/conversations/" + conversationId + "/messages",
                new HttpEntity<>(req3, bearer(a.accessToken())),
                Object.class
        );
        assertThat(res3.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // verify(messageRepository, times(1)).insert(...) was reached
        verify(messageRepository, times(1)).insert(any(), anyLong(), any(), any(), any());

        // Send again with a new clientMsgId: expect 201 and seq == 3 (NOT 4)
        SendMessageRequest req4 = new SendMessageRequest(UUID.randomUUID(), "msg 4");
        ResponseEntity<MessageResponse> res4 = restTemplate.postForEntity(
                "/api/conversations/" + conversationId + "/messages",
                new HttpEntity<>(req4, bearer(a.accessToken())),
                MessageResponse.class
        );
        assertThat(res4.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res4.getBody()).isNotNull();
        assertThat(res4.getBody().seq()).isEqualTo(3L);

        // Then GET messages and assert seqs are exactly [3,2,1]
        ResponseEntity<MessagePage> pageRes = restTemplate.exchange(
                "/api/conversations/" + conversationId + "/messages",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(pageRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pageRes.getBody()).isNotNull();
        List<Long> seqs = pageRes.getBody().messages().stream().map(MessageResponse::seq).toList();
        assertThat(seqs).containsExactly(3L, 2L, 1L);
    }
}
