package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * ADR-0012 step 3, with the hardening context's low limits: sends 3/min per user (2 for guests), new DMs 2/h per
 * user (1 for guests). One budget for WebSocket and REST; only NEW messages and NEW DMs use it. Blocks hide the
 * blocker from DM creation (404) and stop sends in an existing DM in both directions (403); groups are unaffected.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class SendLimitAndBlockTest extends HardeningTestSupport {

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();

    /** [id, accessToken] of a fresh user. */
    private String[] user() {
        String name = "s" + suffix();
        ResponseEntity<AuthResponse> res = http().postForEntity("/api/auth/register",
                new HttpEntity<>(Map.of("username", name, "email", name + "@example.com", "password", PASSWORD,
                        "displayName", name, "inviteCode", INVITE), from(uniqueIp())), AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new String[]{res.getBody().user().id().toString(), res.getBody().accessToken()};
    }

    /** [id, accessToken] of a fresh guest. */
    private String[] guest() throws Exception {
        HttpHeaders h = from(uniqueIp());
        h.setOrigin(AuthCookies.ORIGIN);
        ResponseEntity<String> r = http().postForEntity("/api/auth/guest", new HttpEntity<>(null, h), String.class);
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode b = mapper.readTree(r.getBody());
        return new String[]{b.get("user").get("id").asText(), b.get("accessToken").asText()};
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<String> dm(String token, String otherId) {
        return http().exchange("/api/conversations/direct", HttpMethod.POST,
                new HttpEntity<>(Map.of("otherUserId", otherId), bearer(token)), String.class);
    }

    private String dmId(String token, String otherId) throws Exception {
        ResponseEntity<String> r = dm(token, otherId);
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(r.getBody()).get("id").asText();
    }

    private ResponseEntity<String> send(String token, String conversationId, UUID clientMsgId, String body) {
        return http().exchange("/api/conversations/" + conversationId + "/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("clientMsgId", clientMsgId, "body", body), bearer(token)), String.class);
    }

    private ResponseEntity<String> block(String token, String otherId, HttpMethod method) {
        return http().exchange("/api/users/" + otherId + "/block", method, new HttpEntity<>(bearer(token)),
                String.class);
    }

    private JsonNode wsSend(WsTestClient ws, String conversationId, UUID clientMsgId) throws Exception {
        return wsSend(ws, conversationId, clientMsgId, "ws");
    }

    private JsonNode wsSend(WsTestClient ws, String conversationId, UUID clientMsgId, String body) throws Exception {
        ws.send(mapper.writeValueAsString(Map.of("v", 1, "type", "send", "conversationId", conversationId,
                "clientMsgId", clientMsgId.toString(), "body", body)));
        while (true) {
            String frame = ws.next(5, TimeUnit.SECONDS);
            assertThat(frame).as("a reply to " + clientMsgId).isNotNull();
            JsonNode f = mapper.readTree(frame);
            if (f.has("clientMsgId") && clientMsgId.toString().equals(f.get("clientMsgId").asText())) {
                return f;
            }
        }
    }

    @Test
    void webSocketAndRestShareOneSendBudgetAndTheRejectionSaysWhenToRetry() throws Exception {
        String[] a = user();
        String[] b = user();
        String conv = dmId(a[1], b[0]);
        WsTestClient ws = WsTestClient.connect(port, ticketValue(a[1]));
        try {
            assertThat(send(a[1], conv, UUID.randomUUID(), "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(send(a[1], conv, UUID.randomUUID(), "2").getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(wsSend(ws, conv, UUID.randomUUID()).get("type").asText()).isEqualTo("ack");

            JsonNode refused = wsSend(ws, conv, UUID.randomUUID());
            assertThat(refused.get("type").asText()).isEqualTo("error");
            assertThat(refused.get("code").asText()).isEqualTo("RATE_LIMITED");
            assertThat(refused.get("retryAfterMs").asLong()).isPositive();

            ResponseEntity<String> rest = send(a[1], conv, UUID.randomUUID(), "5");
            assertThat(rest.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(Long.parseLong(rest.getHeaders().getFirst("Retry-After"))).isPositive();
        } finally {
            ws.close();
        }
        assertThat(send(b[1], conv, UUID.randomUUID(), "other user").getStatusCode())
                .as("the budget is per user").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aResendOfACommittedMessageIsAckedBeyondTheLimit() throws Exception {
        String[] a = user();
        String[] b = user();
        String conv = dmId(a[1], b[0]);
        UUID first = UUID.randomUUID();
        ResponseEntity<String> original = send(a[1], conv, first, "1");
        assertThat(original.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(a[1], conv, UUID.randomUUID(), "2").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(a[1], conv, UUID.randomUUID(), "3").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(a[1], conv, UUID.randomUUID(), "4").getStatusCode())
                .as("the budget is used up").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        ResponseEntity<String> resend = send(a[1], conv, first, "1");
        assertThat(resend.getStatusCode().is2xxSuccessful()).as(resend.getBody()).isTrue();
        assertThat(mapper.readTree(resend.getBody()).get("id").asText())
                .isEqualTo(mapper.readTree(original.getBody()).get("id").asText());
        WsTestClient ws = WsTestClient.connect(port, ticketValue(a[1]));
        try {
            JsonNode ack = wsSend(ws, conv, first, "1");
            assertThat(ack.get("type").asText()).as("a WS resend is acked too").isEqualTo("ack");
            assertThat(ack.get("seq").asLong()).isEqualTo(1L);
        } finally {
            ws.close();
        }
    }

    @Test
    void guestsHaveALowerSendLimit() throws Exception {
        String[] g = guest();
        String[] b = user();
        String conv = dmId(g[1], b[0]);
        assertThat(send(g[1], conv, UUID.randomUUID(), "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(g[1], conv, UUID.randomUUID(), "2").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(g[1], conv, UUID.randomUUID(), "3").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void onlyNewlyCreatedDmsCountTowardsTheCap() throws Exception {
        String[] a = user();
        String[] b = user();
        String[] c = user();
        String[] d = user();
        String ab = dmId(a[1], b[0]);
        dmId(a[1], c[0]);
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> again = dm(a[1], b[0]);
            assertThat(again.getStatusCode()).as("reopening an existing DM is free").isEqualTo(HttpStatus.CREATED);
            assertThat(mapper.readTree(again.getBody()).get("id").asText()).isEqualTo(ab);
        }
        ResponseEntity<String> third = dm(a[1], d[0]);
        assertThat(third.getStatusCode()).as("the third NEW DM in the hour").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(dm(d[1], a[0]).getStatusCode()).as("the other user's own budget").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void guestsMayStartOneDmAnHourAndTheirWelcomeDmDoesNotCount() throws Exception {
        String[] g = guest();
        assertThat(dm(g[1], user()[0]).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(dm(g[1], user()[0]).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void aBlockHidesTheBlockerAndStopsSendsBothWaysUntilUnblocked() throws Exception {
        String[] a = user();
        String[] b = user();
        String conv = dmId(a[1], b[0]);
        assertThat(send(b[1], conv, UUID.randomUUID(), "before").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(block(a[1], b[0], HttpMethod.POST).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(block(a[1], b[0], HttpMethod.POST).getStatusCode()).as("idempotent").isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode blocked = mapper.readTree(http().exchange("/api/users/blocked", HttpMethod.GET,
                new HttpEntity<>(bearer(a[1])), String.class).getBody());
        assertThat(blocked.findValuesAsText("id")).containsExactly(b[0]);

        ResponseEntity<String> create = dm(b[1], a[0]);
        assertThat(create.getStatusCode()).as("the blocked user can't tell").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(create.getBody()).contains("USER_NOT_FOUND");
        assertThat(dm(a[1], b[0]).getStatusCode()).as("nor can the blocker reopen it").isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> fromBlocked = send(b[1], conv, UUID.randomUUID(), "x");
        assertThat(fromBlocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fromBlocked.getBody()).contains("RECIPIENT_UNAVAILABLE");
        assertThat(send(a[1], conv, UUID.randomUUID(), "y").getStatusCode())
                .as("the blocker can't send either").isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(block(a[1], b[0], HttpMethod.DELETE).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(send(b[1], conv, UUID.randomUUID(), "after").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(dm(b[1], a[0]).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void blockingYourselfOrAnUnknownUserIsRefused() {
        String[] a = user();
        assertThat(block(a[1], a[0], HttpMethod.POST).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(block(a[1], UUID.randomUUID().toString(), HttpMethod.POST).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void groupsAreUnaffectedByBlocks() throws Exception {
        String[] a = user();
        String[] b = user();
        ResponseEntity<String> group = http().exchange("/api/conversations/group", HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "g", "memberIds", List.of(b[0])), bearer(a[1])), String.class);
        String groupId = mapper.readTree(group.getBody()).get("id").asText();
        block(a[1], b[0], HttpMethod.POST);
        assertThat(send(b[1], groupId, UUID.randomUUID(), "still fine").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
