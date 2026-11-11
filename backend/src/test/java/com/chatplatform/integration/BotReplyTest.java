package com.chatplatform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ADR-0012: the welcome bot marks a guest's message read and answers with one fixed reply, at most once per
 * 10 s per conversation. It runs after commit, so the reply is a normal message with the next seq.
 * The bot finishes its reply before it advances its read cursor, so "read cursor reached seq N" means the
 * bot has finished handling message N.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BotReplyTest extends AbstractIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private JsonNode get(String token, String path) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)),
                String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(r.getBody());
    }

    private long send(String token, String conversationId, String body) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange("/api/conversations/" + conversationId + "/messages",
                HttpMethod.POST, new HttpEntity<>(Map.of("clientMsgId", UUID.randomUUID(), "body", body),
                        bearer(token)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(r.getBody()).get("seq").asLong();
    }

    private long botReadSeq(String token, String conversationId, String botId) throws Exception {
        for (JsonNode c : get(token, "/api/conversations/" + conversationId).get("participantCursors")) {
            if (c.get("userId").asText().equals(botId)) {
                return c.get("lastReadSeq").asLong();
            }
        }
        throw new AssertionError("bot is not a participant");
    }

    private List<String> senders(String token, String conversationId) throws Exception {
        List<String> out = new ArrayList<>();
        get(token, "/api/conversations/" + conversationId + "/messages?after=0&limit=100").get("messages")
                .forEach(m -> out.add(m.get("senderId").asText()));
        return out;
    }

    @Test
    void theBotReadsAndRepliesOnceThenStaysQuietForTenSeconds() throws Exception {
        ResponseEntity<String> created = restTemplate.postForEntity("/api/auth/guest",
                AuthCookies.request(null, AuthCookies.ORIGIN), String.class);
        String token = mapper.readTree(created.getBody()).get("accessToken").asText();
        JsonNode dm = null;
        for (JsonNode c : get(token, "/api/conversations")) {
            if ("DIRECT".equals(c.get("type").asText())) {
                dm = c;
            }
        }
        String dmId = dm.get("id").asText();
        String botId = null;
        for (JsonNode p : dm.get("participants")) {
            if (p.get("isBot").asBoolean()) {
                botId = p.get("id").asText();
            }
        }
        String bot = botId;

        long first = send(token, dmId, "hi bot");
        await("the bot to read message " + first).atMost(Duration.ofSeconds(5))
                .until(() -> botReadSeq(token, dmId, bot) >= first);
        List<String> afterFirst = senders(token, dmId);
        assertThat(afterFirst.getLast()).as("the bot replied").isEqualTo(bot);
        long botMessages = afterFirst.stream().filter(bot::equals).count();

        long second = send(token, dmId, "are you there?");
        await("the bot to read message " + second).atMost(Duration.ofSeconds(5))
                .until(() -> botReadSeq(token, dmId, bot) >= second);
        assertThat(senders(token, dmId).stream().filter(bot::equals).count())
                .as("no second reply within 10 s").isEqualTo(botMessages);
    }
}
