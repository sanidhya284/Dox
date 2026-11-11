package com.chatplatform.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/** ADR-0011: register, login, ticket and failed-handshake limits (low values from HardeningTestSupport). */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RateLimitTest extends HardeningTestSupport {

    @SpyBean
    private PasswordEncoder passwordEncoder;

    @LocalServerPort
    private int port;

    @Test
    void registrationIsLimitedPerIp() {
        String ip = uniqueIp();
        for (int i = 0; i < 3; i++) {
            String n = "r" + suffix();
            assertThat(register(n, n + "@example.com", INVITE, ip).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        String n = "r" + suffix();
        ResponseEntity<String> fourth = register(n, n + "@example.com", INVITE, ip);
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourth.getBody()).contains("RATE_LIMITED");
        assertThat(Long.parseLong(fourth.getHeaders().getFirst("Retry-After"))).isPositive();
        assertThat(register(n, n + "@example.com", INVITE, uniqueIp()).getStatusCode())
                .as("another IP is not affected").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void loginIsLimitedPerIpAndUsernameAndOtherPairsAreUnaffected() {
        String victim = newUser()[0];
        String other = newUser()[0];
        String attacker = uniqueIp();
        assertThat(login(victim, "wrong-password", attacker).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(victim, "wrong-password", attacker).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(victim, PASSWORD, attacker).getStatusCode())
                .as("third try on the same (IP, username), even with the right password")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login(victim, PASSWORD, uniqueIp()).getStatusCode())
                .as("the real user from another IP can still log in").isEqualTo(HttpStatus.OK);
        assertThat(login(other, PASSWORD, attacker).getStatusCode())
                .as("same IP, different username").isEqualTo(HttpStatus.OK);
    }

    @Test
    void loginIsLimitedPerIpAcrossUsernames() {
        String ip = uniqueIp();
        for (int i = 0; i < 6; i++) {
            assertThat(login("nobody" + suffix(), "x-password", ip).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(login("nobody" + suffix(), "x-password", ip).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void aLimitedLoginNeverReachesBcrypt() {
        String user = newUser()[0];
        String ip = uniqueIp();
        login(user, "wrong-password", ip);
        login(user, "wrong-password", ip);
        Mockito.clearInvocations(passwordEncoder);
        assertThat(login(user, "wrong-password", ip).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        Mockito.verify(passwordEncoder, Mockito.never()).matches(any(), any());
    }

    @Test
    void userSearchIsLimitedPerUser() {
        String token = newUser()[1];
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setBearerAuth(token);
        org.springframework.http.HttpEntity<Void> req = new org.springframework.http.HttpEntity<>(h);
        for (int i = 0; i < 3; i++) {
            assertThat(http().exchange("/api/users/search?q=ab", org.springframework.http.HttpMethod.GET, req,
                    String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> fourth = http().exchange("/api/users/search?q=ab",
                org.springframework.http.HttpMethod.GET, req, String.class);
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void guestCreationIsLimitedPerIp() {
        String ip = uniqueIp();
        org.springframework.http.HttpHeaders h = from(ip);
        h.setOrigin(AuthCookies.ORIGIN);
        org.springframework.http.HttpEntity<Void> req = new org.springframework.http.HttpEntity<>(null, h);
        for (int i = 0; i < 2; i++) {
            assertThat(http().postForEntity("/api/auth/guest", req, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<String> third = http().postForEntity("/api/auth/guest", req, String.class);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getBody()).contains("RATE_LIMITED");
        org.springframework.http.HttpHeaders other = from(uniqueIp());
        other.setOrigin(AuthCookies.ORIGIN);
        assertThat(http().postForEntity("/api/auth/guest", new org.springframework.http.HttpEntity<>(null, other),
                String.class).getStatusCode()).as("another IP").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void ticketsAreLimitedPerUser() {
        String token = newUser()[1];
        for (int i = 0; i < 4; i++) {
            assertThat(ticket(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(ticket(token).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ticket(newUser()[1]).getStatusCode()).as("another user").isEqualTo(HttpStatus.OK);
    }

    @Test
    void failedHandshakesFromOneIpAreBlockedBeforeAnyTicketIsConsumed() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 3; i++) {
            assertThat(handshakeStatus(port, "not-a-ticket-" + i, ip)).isEqualTo(401);
        }
        String validTicket = ticketValue(newUser()[1]);
        assertThat(handshakeStatus(port, validTicket, ip)).as("blocked IP").isEqualTo(429);
        assertThat(handshakeStatus(port, validTicket, uniqueIp()))
                .as("the blocked attempt did not consume the ticket").isEqualTo(101);
    }
}
