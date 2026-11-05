package com.chatplatform.integration;

import com.chatplatform.demo.DemoSeeder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-0011: demo1..demo3 and one "Demo room", idempotent; a changed DEMO_PASSWORD resets their passwords. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DemoSeedTest extends AbstractIntegrationTest {

    @Autowired
    private DemoSeeder seeder;

    @Autowired
    private JdbcClient jdbc;

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private HttpStatus login(String user, String password) {
        return HttpStatus.valueOf(restTemplate.postForEntity("/api/auth/login",
                Map.of("username", user, "password", password), String.class).getStatusCode().value());
    }

    @Test
    void seedingTwiceCreatesTheAccountsAndRoomOnceAndANewPasswordReplacesTheOld() {
        seeder.seed("demo-password-one");
        seeder.seed("demo-password-one");

        assertThat(count("SELECT count(*) FROM users WHERE username IN ('demo1','demo2','demo3')")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM conversations c JOIN conversation_participants p "
                + "ON p.conversation_id = c.id JOIN users u ON u.id = p.user_id "
                + "WHERE c.title = 'Demo room' AND u.username = 'demo1'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM conversation_participants p JOIN conversations c "
                + "ON c.id = p.conversation_id WHERE c.title = 'Demo room'")).isEqualTo(3);
        assertThat(login("demo2", "demo-password-one")).isEqualTo(HttpStatus.OK);

        seeder.seed("demo-password-two");
        assertThat(login("demo2", "demo-password-two")).isEqualTo(HttpStatus.OK);
        assertThat(login("demo2", "demo-password-one")).as("the old password stops working")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("SELECT count(*) FROM users WHERE username IN ('demo1','demo2','demo3')")).isEqualTo(3);
    }
}
