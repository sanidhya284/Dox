package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.realtime.service.WsTicketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Frame protocol v1 over a real socket: size limit and the error-vs-close rule (ADR-0006). */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
public class WsProtocolTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService wsTicketService;

    @Autowired
    private ObjectMapper mapper;

    private WsTestClient connectNewUser() throws Exception {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("proto_" + s, "proto_" + s + "@example.com", "password123", "P " + s),
                AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return WsTestClient.connect(port, wsTicketService.issue(res.getBody().user().id()).ticket());
    }

    /** A 4,000-char body with every char written as a JSON \\uXXXX escape: about 24 KB on the wire. */
    private static String fullyEscapedBody() {
        return "\\u00e9".repeat(4000);
    }

    @Test
    void frameOver32KiBIsClosedWith1009() throws Exception {
        WsTestClient c = connectNewUser();
        try {
            c.send("{\"v\":1,\"type\":\"send\",\"body\":\"" + "x".repeat(33 * 1024) + "\"}");
        } catch (IllegalStateException alreadyClosed) {
            // The server may close (1009) while the client is still writing the oversized frame.
        }
        assertThat(c.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(1009);
    }

    @Test
    void maximalEscapedFrameIsReadNotRejectedForSize() throws Exception {
        WsTestClient c = connectNewUser();
        // Unsupported version on purpose: the 4004 proves the ~24 KB frame was read and parsed
        // (a too-small buffer would close with 1009 before parsing).
        String frame = "{\"v\":2,\"type\":\"send\",\"conversationId\":\"" + UUID.randomUUID() + "\",\"clientMsgId\":\""
                + UUID.randomUUID() + "\",\"body\":\"" + fullyEscapedBody() + "\"}";
        assertThat(frame.length()).isGreaterThan(24_000);
        c.send(frame);
        assertThat(c.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(4004);
    }

    @Test
    void unparseableClientMsgIdClosesWith1007() throws Exception {
        WsTestClient c = connectNewUser();
        c.send("{\"v\":1,\"type\":\"send\",\"conversationId\":\"" + UUID.randomUUID()
                + "\",\"clientMsgId\":\"not-a-uuid\",\"body\":\"hi\"}");
        assertThat(c.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(1007);
    }

    @Test
    void notJsonClosesWith1007AndUnknownTypeWith4005() throws Exception {
        WsTestClient a = connectNewUser();
        a.send("hello");
        assertThat(a.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(1007);

        WsTestClient b = connectNewUser();
        b.send("{\"v\":1,\"type\":\"typing\"}");
        assertThat(b.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(4005);
    }

    @Test
    void invalidFieldWithGoodClientMsgIdGetsAnErrorFrameAndTheSocketStaysOpen() throws Exception {
        WsTestClient c = connectNewUser();
        UUID cmid = UUID.randomUUID();
        c.send("{\"v\":1,\"type\":\"send\",\"conversationId\":\"nope\",\"clientMsgId\":\"" + cmid + "\",\"body\":\"hi\"}");

        String frame = c.next(5, TimeUnit.SECONDS);
        assertThat(frame).as("error frame").isNotNull();
        JsonNode err = mapper.readTree(frame);
        assertThat(err.get("type").asText()).isEqualTo("error");
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("clientMsgId").asText()).isEqualTo(cmid.toString());

        // Still usable: a second bad frame gets a second error frame.
        UUID cmid2 = UUID.randomUUID();
        c.send("{\"v\":1,\"type\":\"send\",\"conversationId\":\"" + UUID.randomUUID() + "\",\"clientMsgId\":\""
                + cmid2 + "\",\"body\":\"  \"}");
        JsonNode err2 = mapper.readTree(c.next(5, TimeUnit.SECONDS));
        assertThat(err2.get("clientMsgId").asText()).isEqualTo(cmid2.toString());
        assertThat(c.isOpen()).isTrue();
    }
}
