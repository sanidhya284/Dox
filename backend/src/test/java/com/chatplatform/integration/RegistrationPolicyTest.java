package com.chatplatform.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-0011: invite-only registration, checked before the username/email conflict checks. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RegistrationPolicyTest extends HardeningTestSupport {

    @Autowired
    private JdbcClient jdbc;

    private long users(String username) {
        return jdbc.sql("SELECT count(*) FROM users WHERE username = CAST(:u AS citext)")
                .param("u", username).query(Long.class).single();
    }

    @Test
    void withoutTheRightCodeRegistrationIsRefusedAndNothingIsStored() {
        String n = "p" + suffix();
        ResponseEntity<String> none = register(n, n + "@example.com", null, uniqueIp());
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(none.getBody()).contains("REGISTRATION_CLOSED");
        assertThat(register(n, n + "@example.com", "wrong-code", uniqueIp()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(register(n, n + "@example.com", "", uniqueIp()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users(n)).isZero();
        assertThat(register(n, n + "@example.com", INVITE, uniqueIp()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(users(n)).isEqualTo(1);
    }

    @Test
    void withoutTheCodeNobodyLearnsWhichUsernamesOrEmailsExist() {
        String taken = newUser()[0];
        String n = "p" + suffix();
        assertThat(register(taken, n + "@example.com", "wrong-code", uniqueIp()).getStatusCode())
                .as("taken username, wrong code: 403, not 409").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(register(n, taken + "@example.com", null, uniqueIp()).getStatusCode())
                .as("taken email, no code: 403, not 409").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(register(taken, n + "@example.com", INVITE, uniqueIp()).getStatusCode())
                .as("with the code, the conflict is reported").isEqualTo(HttpStatus.CONFLICT);
    }
}
