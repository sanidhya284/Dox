package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.conversation.dto.CursorRequest;
import com.chatplatform.message.dto.SendMessageRequest;
import com.chatplatform.realtime.service.WsTicketService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5-lite step 2 (ADR-0009, Decision 1): each application meter moves on the right event, and no
 * {@code chat.*} meter carries a tag outside a fixed, bounded set (no user, conversation or session ids).
 * Tests share one registry with other classes, so every assertion is on a delta.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
@AutoConfigureObservability(tracing = false)
public class ChatMetricsTest extends AbstractIntegrationTest {

    /** The only tag keys and values any chat.* meter may carry. */
    static final Map<String, Set<String>> ALLOWED_TAGS = Map.of(
            "channel", Set.of("rest", "ws"),
            "outcome", Set.of("created", "duplicate", "rejected", "issued", "consumed", "advanced", "stale"),
            "reason", Set.of("queue_full"),
            "code", Set.of("1000", "1001", "1007", "1009", "4001", "4002", "4003", "4005", "4006", "other"));

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private WsTicketService wsTicketService;

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
        return h;
    }

    private UUID createDm(AuthResponse a, AuthResponse b) {
        return restTemplate.postForEntity("/api/conversations/direct",
                new HttpEntity<>(new CreateDirectRequest(b.user().id()), bearer(a)), ConversationResponse.class)
                .getBody().id();
    }

    private HttpStatus restSend(AuthResponse u, UUID conv, UUID clientMsgId, String body) {
        return HttpStatus.valueOf(restTemplate.postForEntity("/api/conversations/" + conv + "/messages",
                new HttpEntity<>(new SendMessageRequest(clientMsgId, body), bearer(u)), String.class)
                .getStatusCode().value());
    }

    private double count(String name, String... tags) {
        Counter c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private long timerCount(String name) {
        Timer t = meters.find(name).timer();
        return t == null ? 0 : t.count();
    }

    private double gauge(String name) {
        Gauge g = meters.find(name).gauge();
        return g == null ? Double.NaN : g.value();
    }

    /** Polls until the supplier reaches the expected value (close and unregister happen asynchronously). */
    private static void awaitValue(DoubleSupplier s, double expected, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (s.getAsDouble() != expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(s.getAsDouble()).as(what).isEqualTo(expected);
    }

    private String sendFrame(UUID conv, UUID clientMsgId, String body) {
        return "{\"v\":1,\"type\":\"send\",\"conversationId\":\"" + conv + "\",\"clientMsgId\":\"" + clientMsgId
                + "\",\"body\":\"" + body + "\"}";
    }

    @Test
    void restSendsCountCreatedDuplicateAndRejectedSeparately() {
        AuthResponse a = register("mrA_");
        AuthResponse b = register("mrB_");
        AuthResponse outsider = register("mrO_");
        UUID conv = createDm(a, b);
        double created = count("chat.messages.sent", "channel", "rest", "outcome", "created");
        double duplicate = count("chat.messages.sent", "channel", "rest", "outcome", "duplicate");
        double rejected = count("chat.messages.sent", "channel", "rest", "outcome", "rejected");

        UUID id = UUID.randomUUID();
        assertThat(restSend(a, conv, id, "hello")).isEqualTo(HttpStatus.CREATED);
        assertThat(restSend(a, conv, id, "hello").is2xxSuccessful()).isTrue(); // idempotent retry
        assertThat(restSend(outsider, conv, UUID.randomUUID(), "x")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restSend(a, conv, id, "different body")).isEqualTo(HttpStatus.CONFLICT);

        assertThat(count("chat.messages.sent", "channel", "rest", "outcome", "created") - created).isEqualTo(1);
        assertThat(count("chat.messages.sent", "channel", "rest", "outcome", "duplicate") - duplicate).isEqualTo(1);
        assertThat(count("chat.messages.sent", "channel", "rest", "outcome", "rejected") - rejected).isEqualTo(2);
    }

    @Test
    void wsSendCountsAsWsAndTimesTheAck() throws Exception {
        AuthResponse a = register("mwA_");
        AuthResponse b = register("mwB_");
        UUID conv = createDm(a, b);
        double created = count("chat.messages.sent", "channel", "ws", "outcome", "created");
        double restCreated = count("chat.messages.sent", "channel", "rest", "outcome", "created");
        long acks = timerCount("chat.ws.send.ack");

        WsTestClient s = WsTestClient.connect(port, wsTicketService.issue(a.user().id()).ticket());
        s.send(sendFrame(conv, UUID.randomUUID(), "over ws"));
        assertThat(s.next(5, TimeUnit.SECONDS)).contains("\"type\":\"ack\"");
        s.close();

        assertThat(count("chat.messages.sent", "channel", "ws", "outcome", "created") - created).isEqualTo(1);
        assertThat(count("chat.messages.sent", "channel", "rest", "outcome", "created") - restCreated).isEqualTo(0);
        assertThat(timerCount("chat.ws.send.ack") - acks).isEqualTo(1);
    }

    @Test
    void sessionsGaugeTracksConnectAndClose() throws Exception {
        AuthResponse a = register("mgA_");
        double before = gauge("chat.ws.sessions");
        WsTestClient s1 = WsTestClient.connect(port, wsTicketService.issue(a.user().id()).ticket());
        WsTestClient s2 = WsTestClient.connect(port, wsTicketService.issue(a.user().id()).ticket());
        awaitValue(() -> gauge("chat.ws.sessions"), before + 2, "two sockets registered");
        s1.close();
        s2.close();
        awaitValue(() -> gauge("chat.ws.sessions"), before, "both sockets unregistered");
    }

    @Test
    void closesAreCountedByCode() throws Exception {
        AuthResponse a = register("mcA_");
        double normal = count("chat.ws.closes", "code", "1000");
        double unknownType = count("chat.ws.closes", "code", "4005");

        WsTestClient s1 = WsTestClient.connect(port, wsTicketService.issue(a.user().id()).ticket());
        s1.close();
        WsTestClient s2 = WsTestClient.connect(port, wsTicketService.issue(a.user().id()).ticket());
        s2.send("{\"v\":1,\"type\":\"dance\"}");
        assertThat(s2.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(4005);

        awaitValue(() -> count("chat.ws.closes", "code", "1000") - normal, 1, "normal close counted as 1000");
        awaitValue(() -> count("chat.ws.closes", "code", "4005") - unknownType, 1, "unknown type counted as 4005");
    }

    @Test
    void ticketsCountIssuedConsumedAndRejected() throws Exception {
        AuthResponse a = register("mtA_");
        double issued = count("chat.ws.tickets", "outcome", "issued");
        double consumed = count("chat.ws.tickets", "outcome", "consumed");
        double rejected = count("chat.ws.tickets", "outcome", "rejected");

        ResponseEntity<String> t = restTemplate.exchange("/api/ws/ticket", HttpMethod.POST,
                new HttpEntity<>(bearer(a)), String.class);
        assertThat(t.getStatusCode().is2xxSuccessful()).isTrue();
        String ticket = t.getBody().replaceAll(".*\"ticket\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        WsTestClient.connect(port, ticket).close();
        try {
            WsTestClient.connect(port, ticket); // replay: the ticket is gone
        } catch (Exception expected) {
            // handshake refused
        }

        assertThat(count("chat.ws.tickets", "outcome", "issued") - issued).isEqualTo(1);
        assertThat(count("chat.ws.tickets", "outcome", "consumed") - consumed).isEqualTo(1);
        assertThat(count("chat.ws.tickets", "outcome", "rejected") - rejected).isEqualTo(1);
    }

    @Test
    void cursorUpdatesCountAdvancedAndStale() {
        AuthResponse a = register("muA_");
        AuthResponse b = register("muB_");
        UUID conv = createDm(a, b);
        assertThat(restSend(a, conv, UUID.randomUUID(), "one")).isEqualTo(HttpStatus.CREATED);
        double advanced = count("chat.cursor.updates", "outcome", "advanced");
        double stale = count("chat.cursor.updates", "outcome", "stale");

        for (int i = 0; i < 2; i++) { // the second update moves nothing
            assertThat(restTemplate.postForEntity("/api/conversations/" + conv + "/cursor",
                    new HttpEntity<>(new CursorRequest("read", 1L), bearer(b)), String.class)
                    .getStatusCode().is2xxSuccessful()).isTrue();
        }

        assertThat(count("chat.cursor.updates", "outcome", "advanced") - advanced).isEqualTo(1);
        assertThat(count("chat.cursor.updates", "outcome", "stale") - stale).isEqualTo(1);
    }

    @Test
    void headsRequestsAreCounted() {
        AuthResponse a = register("mhA_");
        double before = count("chat.heads.requests");
        assertThat(restTemplate.exchange("/api/conversations/heads", HttpMethod.GET,
                new HttpEntity<>(bearer(a)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("chat.heads.requests") - before).isEqualTo(1);
    }

    @Test
    void pushExecutorIsInstrumented() {
        assertThat(meters.find("executor.queued").tag("name", "wsPushExecutor").gauge()).isNotNull();
        assertThat(meters.find("executor.active").tag("name", "wsPushExecutor").gauge()).isNotNull();
    }

    /**
     * Cardinality guard: run after the other tests in this class have created meters (order is not
     * guaranteed, so it also creates a few itself). Every chat.* meter must use only allowed tags.
     */
    @Test
    void everyChatMeterUsesOnlyBoundedTags() throws Exception {
        restSendsCountCreatedDuplicateAndRejectedSeparately();
        closesAreCountedByCode();
        int checked = 0;
        for (Meter m : meters.getMeters()) {
            if (!m.getId().getName().startsWith("chat.")) {
                continue;
            }
            checked++;
            for (Tag tag : m.getId().getTags()) {
                assertThat(ALLOWED_TAGS).as("tag key on %s", m.getId()).containsKey(tag.getKey());
                assertThat(ALLOWED_TAGS.get(tag.getKey())).as("tag value on %s", m.getId()).contains(tag.getValue());
            }
        }
        assertThat(checked).as("chat.* meters found").isGreaterThanOrEqualTo(5);
    }

    @Test
    void prometheusScrapeShowsTheApplicationMeters() {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth("metrics", MetricsExposureTest.METRICS_PASSWORD);
        String body = restTemplate.exchange("/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(h), String.class)
                .getBody();
        assertThat(body).contains("chat_ws_sessions").contains("chat_heads_requests_total");
    }
}
