package com.chatplatform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ADR-0013 Decision 4 and 6: with ratelimit.store=redis each limit is N IN TOTAL across both instances, not N per
 * instance. One representative per kind of limit (every limit goes through the same SharedLimiter): login per
 * (IP, user), send per user, new DMs per user, failed handshakes per IP (the check-without-counting kind), the
 * socket cap, and the bot reply throttle.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
@TestPropertySource(properties = {
        "ratelimit.store=redis",
        "ratelimit.login-per-ip-and-user-per-minute=3",
        "ratelimit.send-per-user-per-minute=4",
        "ratelimit.dm-per-user-per-hour=2",
        "ratelimit.failed-handshakes-per-ip-per-minute=3",
        "ws.max-sockets-per-user=3"})
class SharedLimitsTest extends TwoInstanceSupport {

    @Override
    protected Map<String, Object> extraPropertiesB() {
        return Map.of(
                "ratelimit.store", "redis",
                "ratelimit.login-per-ip-and-user-per-minute", 3,
                "ratelimit.send-per-user-per-minute", 4,
                "ratelimit.dm-per-user-per-hour", 2,
                "ratelimit.failed-handshakes-per-ip-per-minute", 3,
                "ws.max-sockets-per-user", 3);
    }

    private ResponseEntity<String> login(org.springframework.boot.test.web.client.TestRestTemplate rest,
                                         String username, String ip) {
        return rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", "password123"),
                        HardeningTestSupport.from(ip)), String.class);
    }

    @Test
    void loginPerIpAndUserIsSharedAcrossInstances() {
        String name = newUser(restA, "sl")[2];
        String ip = HardeningTestSupport.uniqueIp();
        assertThat(login(restA, name, ip).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(restB, name, ip).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(restA, name, ip).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(restB, name, ip).getStatusCode()).as("4th of 3, on the other instance")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void sendPerUserIsOneBudgetAcrossInstances() throws Exception {
        String[] alice = newUser(restA, "ss");
        String[] bob = newUser(restA, "st");
        String conv = group(restA, alice[1], bob[0]);
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            codes.add(send(i % 2 == 0 ? restA : restB, alice[1], conv, "m" + i));
        }
        assertThat(codes).containsOnly(201);
        assertThat(send(restB, alice[1], conv, "fifth")).as("5th of 4, on B").isEqualTo(429);
        assertThat(send(restA, alice[1], conv, "sixth")).isEqualTo(429);
    }

    @Test
    void newDmsPerUserAreSharedAcrossInstances() {
        String[] alice = newUser(restA, "sd");
        String b1 = newUser(restA, "sd")[0];
        String b2 = newUser(restA, "sd")[0];
        String b3 = newUser(restA, "sd")[0];
        assertThat(post(restA, "/api/conversations/direct", alice[1], Map.of("otherUserId", b1))
                .getStatusCode().value()).isEqualTo(201);
        assertThat(post(restB, "/api/conversations/direct", alice[1], Map.of("otherUserId", b2))
                .getStatusCode().value()).isEqualTo(201);
        assertThat(post(restB, "/api/conversations/direct", alice[1], Map.of("otherUserId", b3))
                .getStatusCode()).as("3rd new DM of 2, on B").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void failedHandshakesFromOneIpAreCountedAcrossInstances() throws Exception {
        String ip = HardeningTestSupport.uniqueIp();
        assertThat(HardeningTestSupport.handshakeStatus(portA, "not-a-ticket-1", ip)).isEqualTo(401);
        assertThat(HardeningTestSupport.handshakeStatus(portB, "not-a-ticket-2", ip)).isEqualTo(401);
        assertThat(HardeningTestSupport.handshakeStatus(portA, "not-a-ticket-3", ip)).isEqualTo(401);
        String[] u = newUser(restA, "sh");
        String valid = wsTickets.issue(java.util.UUID.fromString(u[0])).ticket();
        assertThat(HardeningTestSupport.handshakeStatus(portB, valid, ip)).as("blocked on B after 3 failures")
                .isEqualTo(429);
    }

    @Test
    void theSocketCapCountsSocketsOnBothInstances() throws Exception {
        String[] u = newUser(restA, "sc");
        WsTestClient a1 = socket(portA, u[0]);
        WsTestClient a2 = socket(portA, u[0]);
        WsTestClient b1 = socket(portB, u[0]);
        WsTestClient b2 = socket(portB, u[0]); // the 4th of 3
        CloseStatus closed = b2.closed.get(5, TimeUnit.SECONDS);
        assertThat(closed.getCode()).isEqualTo(4007);
        Thread.sleep(300);
        assertThat(List.of(a1.isOpen(), a2.isOpen(), b1.isOpen())).containsOnly(true);

        a1.close(); // a slot freed on A can be used on B
        a1.closed.get(5, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            WsTestClient again = socket(portB, u[0]);
            Thread.sleep(300);
            assertThat(again.isOpen()).isTrue();
            again.close();
        });
        a2.close();
        b1.close();
    }

    @Test
    void theBotRepliesOnceForTwoMessagesCommittedOnDifferentInstances() throws Exception {
        ResponseEntity<String> created = restA.postForEntity("/api/auth/guest",
                AuthCookies.request(null, AuthCookies.ORIGIN), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = json.readTree(created.getBody()).get("accessToken").asText();
        JsonNode dm = null;
        for (JsonNode c : json.readTree(get(restA, "/api/conversations", token).getBody())) {
            if ("DIRECT".equals(c.get("type").asText())) {
                dm = c;
            }
        }
        String dmId = dm.get("id").asText();
        String bot = null;
        for (JsonNode p : dm.get("participants")) {
            if (p.get("isBot").asBoolean()) {
                bot = p.get("id").asText();
            }
        }
        String botId = bot;

        long first = sendSeq(restA, token, dmId, "hi from A");
        await().atMost(Duration.ofSeconds(5)).until(() -> botReadSeq(token, dmId, botId) >= first);
        long repliesAfterFirst = botMessages(token, dmId, botId);

        long second = sendSeq(restB, token, dmId, "hi from B"); // within 10 s, committed on the OTHER instance
        await().atMost(Duration.ofSeconds(5)).until(() -> botReadSeq(token, dmId, botId) >= second);
        assertThat(botMessages(token, dmId, botId)).as("B's bot shares A's throttle: no second reply")
                .isEqualTo(repliesAfterFirst);
    }

    private long sendSeq(org.springframework.boot.test.web.client.TestRestTemplate rest, String token, String conv,
                         String body) throws Exception {
        ResponseEntity<String> r = post(rest, "/api/conversations/" + conv + "/messages", token,
                Map.of("clientMsgId", java.util.UUID.randomUUID(), "body", body));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json.readTree(r.getBody()).get("seq").asLong();
    }

    private long botReadSeq(String token, String conv, String botId) throws Exception {
        for (JsonNode c : json.readTree(get(restA, "/api/conversations/" + conv, token).getBody())
                .get("participantCursors")) {
            if (c.get("userId").asText().equals(botId)) {
                return c.get("lastReadSeq").asLong();
            }
        }
        throw new AssertionError("bot is not a participant");
    }

    private long botMessages(String token, String conv, String botId) throws Exception {
        long n = 0;
        for (JsonNode m : json.readTree(get(restA, "/api/conversations/" + conv + "/messages?after=0&limit=100",
                token).getBody()).get("messages")) {
            n += m.get("senderId").asText().equals(botId) ? 1 : 0;
        }
        return n;
    }
}
