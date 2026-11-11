package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.guest.AccountRemovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0012 Decision 9: an access token stays valid for up to 15 minutes after its account is removed. During
 * that window the removed account gets 401 TOKEN_INVALID for a WebSocket ticket, a new DM or group, and a send
 * (a send in a conversation it was in would otherwise be 404; 401 tells the client to drop its session).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RemovedCallerTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRemovalService removal;

    private final ObjectMapper mapper = new ObjectMapper();

    private AuthResponse register(String prefix) {
        String s = UUID.randomUUID().toString().substring(0, 8);
        return restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(prefix + s, prefix + s + "@example.com", "password123", prefix + s),
                AuthResponse.class).getBody();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<String> post(String token, String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, bearer(token)), String.class);
    }

    @Test
    void aRemovedAccountWithAStillValidAccessTokenCanStartNothingAndSendNothing() throws Exception {
        AuthResponse removed = register("rm");
        AuthResponse other = register("ok");
        AuthResponse third = register("ok");
        ResponseEntity<String> dm = post(removed.accessToken(), "/api/conversations/direct",
                Map.of("otherUserId", other.user().id()));
        String dmId = mapper.readTree(dm.getBody()).get("id").asText();
        assertThat(post(removed.accessToken(), "/api/ws/ticket", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        removal.removeAccount(removed.user().username());

        ResponseEntity<String> ticket = post(removed.accessToken(), "/api/ws/ticket", null);
        assertThat(ticket.getStatusCode()).as("ticket").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ticket.getBody()).contains("TOKEN_INVALID");

        ResponseEntity<String> newDm = post(removed.accessToken(), "/api/conversations/direct",
                Map.of("otherUserId", third.user().id()));
        assertThat(newDm.getStatusCode()).as("new DM").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(newDm.getBody()).contains("TOKEN_INVALID");

        ResponseEntity<String> group = post(removed.accessToken(), "/api/conversations/group",
                Map.of("title", "g", "memberIds", List.of(third.user().id())));
        assertThat(group.getStatusCode()).as("new group").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(group.getBody()).contains("TOKEN_INVALID");

        ResponseEntity<String> send = post(removed.accessToken(), "/api/conversations/" + dmId + "/messages",
                Map.of("clientMsgId", UUID.randomUUID(), "body", "still here?"));
        assertThat(send.getStatusCode()).as("send").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send.getBody()).contains("TOKEN_INVALID");

        assertThat(post(other.accessToken(), "/api/ws/ticket", null).getStatusCode())
                .as("other users are unaffected").isEqualTo(HttpStatus.OK);
    }
}
