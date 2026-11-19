package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.realtime.service.WsTicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default (realtime.bus=local, as on the live single-instance server): nothing crosses instances, and nothing
 * contacts Redis. Two instances with the local bus still store every message; a client on the other instance
 * recovers it from history (the Phase 3 sync), it just isn't pushed.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
@TestPropertySource(properties = "realtime.bus=local")
class LocalBusTwoInstanceTest extends TwoInstanceSupport {

    @Autowired
    private WsTicketService tickets;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected String busModeB() {
        return "local";
    }

    @Test
    void withTheLocalBusNothingCrossesButTheMessageIsStored() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        AuthResponse alice = restA.postForEntity("/api/auth/register",
                new RegisterRequest("la" + s, "la" + s + "@example.com", "password123", "A"), AuthResponse.class)
                .getBody();
        AuthResponse bob = restA.postForEntity("/api/auth/register",
                new RegisterRequest("lb" + s, "lb" + s + "@example.com", "password123", "B"), AuthResponse.class)
                .getBody();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(alice.accessToken());
        String conv = mapper.readTree(restA.exchange("/api/conversations/group", HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "l", "memberIds", List.of(bob.user().id())), h), String.class)
                .getBody()).get("id").asText();
        WsTestClient bobOnB = WsTestClient.connect(portB, tickets.issue(bob.user().id()).ticket());
        Thread.sleep(150);

        ResponseEntity<String> sent = restA.exchange("/api/conversations/" + conv + "/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("clientMsgId", UUID.randomUUID(), "body", "stays local"), h), String.class);
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String frame;
        boolean pushed = false;
        while ((frame = bobOnB.next(1500, TimeUnit.MILLISECONDS)) != null) {
            pushed |= frame.contains("\"type\":\"message\"");
        }
        assertThat(pushed).as("no cross-instance push with the local bus").isFalse();

        HttpHeaders hb = new HttpHeaders();
        hb.setBearerAuth(bob.accessToken());
        String page = restB.exchange("/api/conversations/" + conv + "/messages?after=0&limit=10", HttpMethod.GET,
                new HttpEntity<>(hb), String.class).getBody();
        assertThat(page).as("recoverable from history on the other instance").contains("stays local");
    }
}
