package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.realtime.service.WsTicketService;
import com.chatplatform.realtime.websocket.ConnectionRegistry;
import com.chatplatform.realtime.websocket.HeartbeatMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Heartbeats against a real client (which answers pings automatically). Interval 0.3 s, timeout 1 s.
 * Counted on the SERVER side: pings the server sent and pongs it received.
 */
@TestPropertySource(properties = {"ws.heartbeat.interval=PT0.3S", "ws.heartbeat.timeout=PT1S"})
@Timeout(value = 2, unit = TimeUnit.MINUTES)
public class WsHeartbeatTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private HeartbeatMonitor monitor;

    @Autowired
    private ConnectionRegistry registry;

    @Test
    void answeringClientStaysOpenWellPastTheTimeoutAndTheServerSeesItsPongs() throws Exception {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("hb_" + s, "hb_" + s + "@example.com", "password123", "HB " + s),
                AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID userId = res.getBody().user().id();
        long pingsBefore = monitor.pingsSent();
        long pongsBefore = monitor.pongsReceived();

        WsTestClient c = WsTestClient.connect(port, wsTicketService.issue(userId).ticket());
        Thread.sleep(3000); // 3 x the 1 s timeout, about 10 heartbeat intervals; the client sends no frames

        long pings = monitor.pingsSent() - pingsBefore;
        long pongs = monitor.pongsReceived() - pongsBefore;
        assertThat(c.isOpen()).as("an answering client is never closed as idle").isTrue();
        assertThat(registry.sessionIdsFor(userId)).hasSize(1);
        assertThat(pings).as("server-side pings sent in 3 s at 0.3 s intervals").isGreaterThanOrEqualTo(5);
        assertThat(pongs).as("server-side pongs received").isGreaterThanOrEqualTo(pings - 2);
    }
}
