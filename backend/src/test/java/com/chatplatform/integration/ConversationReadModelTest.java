package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0012: conversation responses carry everything the UI needs to render names and previews:
 * {@code participants} (every member who ever joined, with a {@code left} flag) and {@code lastMessage}.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ConversationReadModelTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    private AuthResponse register(String prefix) {
        String s = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(s, s + "@example.com", "password123", "Name " + s), AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private HttpHeaders bearer(AuthResponse u) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(u.accessToken());
        return h;
    }

    private UUID group(AuthResponse owner, AuthResponse... members) throws Exception {
        List<UUID> ids = java.util.Arrays.stream(members).map(m -> m.user().id()).toList();
        ResponseEntity<String> r = restTemplate.exchange("/api/conversations/group", HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "G", "memberIds", ids), bearer(owner)), String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(r.getBody()).isTrue();
        return UUID.fromString(mapper.readTree(r.getBody()).get("id").asText());
    }

    private void send(AuthResponse u, UUID conv, String body) {
        ResponseEntity<String> r = restTemplate.exchange("/api/conversations/" + conv + "/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("clientMsgId", UUID.randomUUID().toString(), "body", body), bearer(u)),
                String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(r.getBody()).isTrue();
    }

    private JsonNode get(AuthResponse u, UUID conv) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange("/api/conversations/" + conv, HttpMethod.GET,
                new HttpEntity<>(bearer(u)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(r.getBody());
    }

    private JsonNode fromList(AuthResponse u, UUID conv) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange("/api/conversations", HttpMethod.GET,
                new HttpEntity<>(bearer(u)), String.class);
        for (JsonNode c : mapper.readTree(r.getBody())) {
            if (c.get("id").asText().equals(conv.toString())) {
                return c;
            }
        }
        throw new AssertionError("conversation not in the list");
    }

    private static JsonNode participant(JsonNode conv, UUID id) {
        for (JsonNode p : conv.get("participants")) {
            if (p.get("id").asText().equals(id.toString())) {
                return p;
            }
        }
        throw new AssertionError("no participant " + id + " in " + conv.get("participants"));
    }

    @Test
    void participantsIncludeMembersWhoLeftAndDeletedAccountsWithNamesAndNoEmail() throws Exception {
        AuthResponse a = register("rmA");
        AuthResponse b = register("rmB");
        AuthResponse c = register("rmC");
        UUID conv = group(a, b, c);
        send(c, conv, "hello from c");
        jdbc.sql("UPDATE conversation_participants SET left_at = now() WHERE conversation_id = :cv AND user_id = :u")
                .param("cv", conv).param("u", c.user().id()).update();
        jdbc.sql("UPDATE users SET deleted_at = now() WHERE id = :id").param("id", b.user().id()).update();

        for (JsonNode view : List.of(get(a, conv), fromList(a, conv))) {
            assertThat(view.get("participants")).hasSize(3);
            JsonNode pc = participant(view, c.user().id());
            assertThat(pc.get("left").asBoolean()).isTrue();
            assertThat(pc.get("username").asText()).isEqualTo(c.user().username());
            assertThat(pc.get("displayName").asText()).isEqualTo("Name " + c.user().username());
            assertThat(participant(view, b.user().id()).get("isDeleted").asBoolean()).isTrue();
            assertThat(participant(view, a.user().id()).get("left").asBoolean()).isFalse();
            assertThat(view.toString()).doesNotContain("@example.com");
        }
    }

    @Test
    void lastMessageIsTheNewestMessageAndIsNullForAnEmptyConversation() throws Exception {
        AuthResponse a = register("lmA");
        AuthResponse b = register("lmB");
        UUID conv = group(a, b);
        assertThat(get(a, conv).get("lastMessage").isNull()).isTrue();
        send(a, conv, "first");
        send(b, conv, "second");
        for (JsonNode view : List.of(get(a, conv), fromList(a, conv))) {
            JsonNode last = view.get("lastMessage");
            assertThat(last.get("preview").asText()).isEqualTo("second");
            assertThat(last.get("seq").asLong()).isEqualTo(2);
            assertThat(last.get("senderId").asText()).isEqualTo(b.user().id().toString());
            assertThat(last.get("createdAt").isNull()).isFalse();
        }
    }

    @Test
    void previewIsTruncatedByCodePointsNeverSplittingASurrogatePair() throws Exception {
        AuthResponse a = register("pvA");
        AuthResponse b = register("pvB");
        UUID conv = group(a, b);
        String face = new String(Character.toChars(0x1F600)); // one code point, two UTF-16 chars
        send(a, conv, face.repeat(130));
        String preview = get(a, conv).get("lastMessage").get("preview").asText();
        assertThat(preview).isEqualTo(face.repeat(120) + "…");
        assertThat(preview.codePointCount(0, preview.length())).isEqualTo(121);
    }
}
