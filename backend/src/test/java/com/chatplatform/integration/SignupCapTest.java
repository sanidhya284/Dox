package com.chatplatform.integration;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0012: global caps that fail closed. Sign-ups are capped per hour across ALL IPs; live guests are capped
 * in total (DEMO_BUSY). Its own context: a sign-up cap of 3 and a live-guest cap of 1.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@TestPropertySource(properties = {"ratelimit.signup-global-per-hour=3", "guest.max-live=1"})
class SignupCapTest extends HardeningTestSupport {

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void signUpsAreCappedGloballyAcrossIps() {
        for (int i = 0; i < 3; i++) {
            String n = "cap" + suffix();
            assertThat(register(n, n + "@example.com", INVITE, uniqueIp()).getStatusCode())
                    .as("sign-up " + i).isEqualTo(HttpStatus.CREATED);
        }
        String n = "cap" + suffix();
        ResponseEntity<String> fourth = register(n, n + "@example.com", INVITE, uniqueIp());
        assertThat(fourth.getStatusCode()).as("4th sign-up from a NEW ip").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourth.getBody()).contains("RATE_LIMITED");
        assertThat(fourth.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(meters.get("chat.signups").tag("outcome", "capped").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("chat.signups").tag("outcome", "created").counter().count()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void guestsBeyondTheLiveCapGetDemoBusy() {
        String s = UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("""
                INSERT INTO users (username, email, password_hash, display_name, is_guest)
                VALUES (:u, :e, '!', 'Guest', true)
                """).param("u", "guest-" + s).param("e", "guest-" + s + "@guest.invalid").update();

        HttpHeaders h = from(uniqueIp());
        h.setOrigin(AuthCookies.ORIGIN);
        ResponseEntity<String> r = http().postForEntity("/api/auth/guest", new HttpEntity<>(null, h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(r.getBody()).contains("DEMO_BUSY");
        assertThat(meters.get("chat.guests.live").gauge().value()).isGreaterThanOrEqualTo(1);
    }
}
