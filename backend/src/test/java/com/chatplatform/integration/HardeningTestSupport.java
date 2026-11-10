package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 6 hardening tests share one context with LOW limits, invite-only registration and a socket cap of 2
 * (ADR-0011, ADR-0006). Requests from the test JVM come from 127.0.0.1, a trusted proxy for Tomcat's
 * RemoteIpValve, so X-Forwarded-For sets the client IP the app sees: each test uses its own fake IPs.
 */
@TestPropertySource(properties = {
        "registration.mode=invite",
        "registration.invite-code=" + HardeningTestSupport.INVITE,
        "ratelimit.register-per-ip-per-hour=3",
        "ratelimit.login-per-ip-per-minute=6",
        "ratelimit.login-per-ip-and-user-per-minute=2",
        "ratelimit.ticket-per-user-per-minute=4",
        "ratelimit.failed-handshakes-per-ip-per-minute=3",
        "ratelimit.search-per-user-per-minute=3",
        "ratelimit.guest-per-ip-per-hour=2",
        "ws.max-sockets-per-user=2"
})
abstract class HardeningTestSupport extends AbstractIntegrationTest {

    static final String INVITE = "test-invite-code-123";
    static final String PASSWORD = "Password123!";

    @org.springframework.boot.test.web.server.LocalServerPort
    private int serverPort;

    private org.springframework.boot.test.web.client.TestRestTemplate http;

    /**
     * The JDK HTTP client, not the default Apache HttpClient 5: Apache's default retry strategy honours
     * Retry-After on 429 and would silently wait (up to an hour here) instead of returning the 429.
     */
    org.springframework.boot.test.web.client.TestRestTemplate http() {
        if (http == null) {
            http = new org.springframework.boot.test.web.client.TestRestTemplate(
                    new org.springframework.boot.web.client.RestTemplateBuilder()
                            .requestFactory(() -> new org.springframework.http.client.JdkClientHttpRequestFactory())
                            .rootUri("http://localhost:" + serverPort));
        }
        return http;
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static HttpHeaders from(String ip) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Forwarded-For", ip);
        return h;
    }

    ResponseEntity<String> register(String username, String email, String inviteCode, String ip) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("username", username, "email", email,
                "password", PASSWORD, "displayName", username));
        if (inviteCode != null) {
            body.put("inviteCode", inviteCode);
        }
        return http().postForEntity("/api/auth/register", new HttpEntity<>(body, from(ip)), String.class);
    }

    /** Registers a fresh user with the invite code from a fresh IP; returns username and access token. */
    String[] newUser() {
        String name = "h" + suffix();
        ResponseEntity<AuthResponse> res = http().postForEntity("/api/auth/register",
                new HttpEntity<>(Map.of("username", name, "email", name + "@example.com", "password", PASSWORD,
                        "displayName", name, "inviteCode", INVITE), from(uniqueIp())), AuthResponse.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("register failed: " + res.getStatusCode());
        }
        return new String[]{name, res.getBody().accessToken()};
    }

    ResponseEntity<String> login(String username, String password, String ip) {
        return http().postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), from(ip)), String.class);
    }

    ResponseEntity<String> ticket(String accessToken) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(accessToken);
        return http().postForEntity("/api/ws/ticket", new HttpEntity<>(null, h), String.class);
    }

    String ticketValue(String accessToken) {
        String json = ticket(accessToken).getBody();
        int i = json.indexOf("\"ticket\":\"") + 10;
        return json.substring(i, json.indexOf('"', i));
    }

    private static final java.util.concurrent.atomic.AtomicInteger IP_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /** A unique fake IP per call (198.18.0.0/15, the benchmarking range). */
    static String uniqueIp() {
        int n = IP_SEQ.incrementAndGet();
        return "198.18." + (n / 250) + "." + (1 + n % 250);
    }

    /** Raw WebSocket handshake: returns the HTTP status line's code (101 = upgraded). */
    static int handshakeStatus(int port, String ticket, String ip) throws Exception {
        try (Socket s = new Socket("localhost", port)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write(("GET /ws?ticket=" + ticket + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                    + "Origin: http://localhost:3000\r\nX-Forwarded-For: " + ip + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }
}
