package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.guest.AccountRemovalService;
import com.chatplatform.realtime.service.WsTicketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ADR-0013: a user can be on either instance. Pushes (message, cursor) and the single "close user" path cross
 * instances over the Redis bus, each exactly once per socket: the origin instance delivers locally and ignores its
 * own echo; the other instance delivers what it receives. The origin socket gets only the ack.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class CrossInstanceDeliveryTest extends TwoInstanceSupport {

    @Autowired
    private WsTicketService tickets;

    @Autowired
    private JdbcClient jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    /** [id, accessToken, refreshToken] */
    private String[] user(String prefix) {
        String s = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<AuthResponse> r = restA.postForEntity("/api/auth/register",
                new RegisterRequest(prefix + s, prefix + s + "@example.com", "password123", prefix + s),
                AuthResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new String[]{r.getBody().user().id().toString(), r.getBody().accessToken(), AuthCookies.refreshToken(r)};
    }

    @Override
    protected WsTestClient socket(int port, String userId) throws Exception {
        WsTestClient c = WsTestClient.connect(port, tickets.issue(UUID.fromString(userId)).ticket());
        Thread.sleep(150); // registered on its instance before anything is pushed
        return c;
    }

    /** Every frame of the given type that arrives within the window (to prove "exactly once"). */
    private List<JsonNode> framesOfType(WsTestClient ws, String type, Duration window) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        long end = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < end) {
            String f = ws.next(Math.max(1, (end - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
            if (f == null) {
                break;
            }
            JsonNode n = mapper.readTree(f);
            if (type.equals(n.get("type").asText())) {
                out.add(n);
            }
        }
        return out;
    }

    private static String sendFrame(String conversationId, String clientMsgId, String body) {
        return "{\"v\":1,\"type\":\"send\",\"conversationId\":\"" + conversationId + "\",\"clientMsgId\":\""
                + clientMsgId + "\",\"body\":\"" + body + "\"}";
    }

    @Test
    void aWebSocketSendOnAReachesEverySocketOnBothInstancesExactlyOnce() throws Exception {
        String[] alice = user("xa");
        String[] bob = user("xb");
        String[] cara = user("xc");
        String conv = group(restA, alice[1], bob[0], cara[0]);

        WsTestClient aliceOnA = socket(portA, alice[0]);   // the origin socket
        WsTestClient aliceOnB = socket(portB, alice[0]);   // alice's other tab, on the other instance
        WsTestClient bobOnB = socket(portB, bob[0]);
        WsTestClient caraOnA = socket(portA, cara[0]);     // a recipient on the ORIGIN instance

        String cmid = UUID.randomUUID().toString();
        aliceOnA.send(sendFrame(conv, cmid, "hello across"));

        assertThat(framesOfType(aliceOnA, "ack", Duration.ofSeconds(3))).hasSize(1);
        Duration window = Duration.ofSeconds(3);
        for (var c : List.of(aliceOnB, bobOnB, caraOnA)) {
            List<JsonNode> pushes = framesOfType(c, "message", window);
            assertThat(pushes).as("exactly one push per socket").hasSize(1);
            assertThat(pushes.getFirst().get("message").get("body").asText()).isEqualTo("hello across");
        }
        assertThat(framesOfType(aliceOnA, "message", Duration.ofMillis(500)))
                .as("the origin socket gets the ack, never its own push").isEmpty();
    }

    @Test
    void aRestSendOnBReachesASocketOnA() throws Exception {
        String[] alice = user("ra");
        String[] bob = user("rb");
        String conv = group(restB, bob[1], alice[0]);
        WsTestClient aliceOnA = socket(portA, alice[0]);

        ResponseEntity<String> sent = restB.exchange("/api/conversations/" + conv + "/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("clientMsgId", UUID.randomUUID(), "body", "from B"), bearer(bob[1])),
                String.class);
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<JsonNode> pushes = framesOfType(aliceOnA, "message", Duration.ofSeconds(3));
        assertThat(pushes).hasSize(1);
        assertThat(pushes.getFirst().get("message").get("body").asText()).isEqualTo("from B");
    }

    @Test
    void aCursorAdvancedOnBReachesASocketOnA() throws Exception {
        String[] alice = user("ca");
        String[] bob = user("cb");
        String conv = group(restA, alice[1], bob[0]);
        restA.exchange("/api/conversations/" + conv + "/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("clientMsgId", UUID.randomUUID(), "body", "read me"), bearer(alice[1])),
                String.class);
        WsTestClient aliceOnA = socket(portA, alice[0]);

        ResponseEntity<String> cursor = restB.exchange("/api/conversations/" + conv + "/cursor", HttpMethod.POST,
                new HttpEntity<>(Map.of("kind", "read", "seq", 1), bearer(bob[1])), String.class);
        assertThat(cursor.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<JsonNode> frames = framesOfType(aliceOnA, "cursor", Duration.ofSeconds(3));
        assertThat(frames).hasSize(1);
        assertThat(frames.getFirst().get("userId").asText()).isEqualTo(bob[0]);
        assertThat(frames.getFirst().get("lastReadSeq").asLong()).isEqualTo(1L);
    }

    @Test
    void aRefreshReplayOnBClosesTheUsersSocketOnAWith4002() throws Exception {
        String[] alice = user("ta");
        WsTestClient aliceOnA = socket(portA, alice[0]);
        assertThat(AuthCookies.refresh(restB, alice[2]).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(AuthCookies.refresh(restB, alice[2]).getStatusCode())
                .as("replay of the rotated token").isEqualTo(HttpStatus.UNAUTHORIZED);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(aliceOnA.closed).isDone();
            assertThat(aliceOnA.closed.get().getCode()).isEqualTo(4002);
        });
    }

    @Test
    void aGuestExpiredOnBClosesItsSocketOnAWith4008() throws Exception {
        ResponseEntity<String> g = restA.postForEntity("/api/auth/guest",
                AuthCookies.request(null, AuthCookies.ORIGIN), String.class);
        String guestId = mapper.readTree(g.getBody()).get("user").get("id").asText();
        WsTestClient guestOnA = socket(portA, guestId);
        jdbc.sql("UPDATE users SET created_at = now() - interval '25 hours' WHERE id = :id")
                .param("id", UUID.fromString(guestId)).update();

        instanceB.getBean(AccountRemovalService.class).removeExpiredGuests(); // the cleanup ran on B

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(guestOnA.closed).isDone();
            assertThat(guestOnA.closed.get().getCode()).isEqualTo(4008);
        });
    }

    @Test
    void anAccountRemovedOnBClosesItsSocketOnAWith4002() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        AuthResponse r = restA.postForEntity("/api/auth/register",
                new RegisterRequest("ar" + s, "ar" + s + "@example.com", "password123", "X"), AuthResponse.class)
                .getBody();
        WsTestClient onA = socket(portA, r.user().id().toString());

        // What the runbook's admin process does (with REALTIME_BUS=redis): the removal runs elsewhere.
        instanceB.getBean(AccountRemovalService.class).removeAccount("ar" + s);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(onA.closed).isDone();
            assertThat(onA.closed.get().getCode()).isEqualTo(4002);
        });
    }
}
