package com.chatplatform.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-0006: at most ws.max-sockets-per-user (2 here) open sockets per user; the next one is closed with 4007. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SocketCapTest extends HardeningTestSupport {

    @LocalServerPort
    private int port;

    @Test
    void theSocketOverTheCapIsClosedWith4007AndTheOthersStayOpen() throws Exception {
        String token = newUser()[1];
        WsTestClient first = WsTestClient.connect(port, ticketValue(token));
        WsTestClient second = WsTestClient.connect(port, ticketValue(token));
        WsTestClient third = WsTestClient.connect(port, ticketValue(token));

        CloseStatus closed = third.closed.get(5, TimeUnit.SECONDS);
        assertThat(closed.getCode()).isEqualTo(4007);
        assertThat(first.isOpen()).isTrue();
        assertThat(second.isOpen()).isTrue();

        first.close();
        first.closed.get(5, TimeUnit.SECONDS);
        WsTestClient replacement = WsTestClient.connect(port, ticketValue(token));
        Thread.sleep(300); // a rejected socket is closed right after the upgrade; give it the chance
        assertThat(replacement.isOpen()).as("a freed slot can be used again").isTrue();
        second.close();
        replacement.close();
    }

    @Test
    void theCapIsPerUser() throws Exception {
        String a = newUser()[1];
        String b = newUser()[1];
        WsTestClient a1 = WsTestClient.connect(port, ticketValue(a));
        WsTestClient a2 = WsTestClient.connect(port, ticketValue(a));
        WsTestClient b1 = WsTestClient.connect(port, ticketValue(b));
        Thread.sleep(300);
        assertThat(b1.isOpen()).isTrue();
        a1.close();
        a2.close();
        b1.close();
    }
}
