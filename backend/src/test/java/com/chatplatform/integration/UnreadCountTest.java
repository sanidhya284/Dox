package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.message.dto.SendMessageRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Unread counts exclude your own messages; cursors are visible per participant (ADR-0007). */
public class UnreadCountTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper mapper;

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

    private void send(AuthResponse u, UUID conv, String body) {
        ResponseEntity<String> r = restTemplate.postForEntity("/api/conversations/" + conv + "/messages",
                new HttpEntity<>(new SendMessageRequest(UUID.randomUUID(), body), bearer(u)), String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private JsonNode single(AuthResponse u, UUID conv) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange("/api/conversations/" + conv, HttpMethod.GET,
                new HttpEntity<>(bearer(u)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(r.getBody());
    }

    private JsonNode fromList(AuthResponse u, UUID conv) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange("/api/conversations", HttpMethod.GET,
                new HttpEntity<>(bearer(u)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode c : mapper.readTree(r.getBody())) {
            if (c.get("id").asText().equals(conv.toString())) {
                return c;
            }
        }
        throw new AssertionError("conversation not in list");
    }

    private static JsonNode cursorOf(JsonNode conversation, UUID user) {
        for (JsonNode p : conversation.get("participantCursors")) {
            if (p.get("userId").asText().equals(user.toString())) {
                return p;
            }
        }
        throw new AssertionError("no cursor for " + user);
    }

    @Test
    void unreadCountsExcludeYourOwnMessagesAndFollowTheReadCursor() throws Exception {
        AuthResponse a = register("urA_");
        AuthResponse b = register("urB_");
        UUID conv = restTemplate.postForEntity("/api/conversations/direct",
                new HttpEntity<>(new CreateDirectRequest(b.user().id()), bearer(a)), ConversationResponse.class)
                .getBody().id();
        send(a, conv, "a1");
        send(a, conv, "a2");
        send(a, conv, "a3");
        send(b, conv, "b1");
        send(b, conv, "b2");

        assertThat(single(a, conv).get("unreadCount").asLong()).as("A sees only B's 2").isEqualTo(2);
        assertThat(fromList(a, conv).get("unreadCount").asLong()).as("list agrees").isEqualTo(2);
        assertThat(single(b, conv).get("unreadCount").asLong()).as("B sees only A's 3").isEqualTo(3);
        assertThat(fromList(b, conv).get("unreadCount").asLong()).isEqualTo(3);
        assertThat(fromList(a, conv).get("myLastReadSeq").asLong()).isZero();

        ResponseEntity<String> read = restTemplate.postForEntity("/api/conversations/" + conv + "/cursor",
                new HttpEntity<>("{\"kind\":\"read\",\"seq\":5}", bearer(a)), String.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(single(a, conv).get("unreadCount").asLong()).isZero();
        assertThat(fromList(a, conv).get("unreadCount").asLong()).isZero();
        assertThat(fromList(a, conv).get("myLastReadSeq").asLong()).isEqualTo(5);
        assertThat(single(b, conv).get("unreadCount").asLong()).as("B unaffected by A's cursor").isEqualTo(3);

        JsonNode seenByB = single(b, conv);
        assertThat(cursorOf(seenByB, a.user().id()).get("lastReadSeq").asLong()).isEqualTo(5);
        assertThat(cursorOf(seenByB, a.user().id()).get("lastDeliveredSeq").asLong()).isEqualTo(5);
        assertThat(cursorOf(seenByB, b.user().id()).get("lastReadSeq").asLong()).isZero();
    }
}
