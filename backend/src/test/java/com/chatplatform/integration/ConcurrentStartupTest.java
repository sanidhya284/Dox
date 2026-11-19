package com.chatplatform.integration;

import com.chatplatform.ChatPlatformApplication;
import com.chatplatform.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0013: two instances may start at the same moment. Flyway's lock makes them migrate exactly once; the bot and
 * demo seeding runs under a Postgres advisory lock, so the second instance waits and then finds the accounts instead
 * of failing on a duplicate key.
 */
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class ConcurrentStartupTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void twoInstancesStartingTogetherOnAnEmptyDatabaseMigrateOnceAndSeedOnce() throws Exception {
        String db = "startup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.sql("CREATE DATABASE " + db).update();
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ConfigurableApplicationContext>> starts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            starts.add(pool.submit(() -> {
                Map<String, Object> props = new HashMap<>();
                props.put("server.port", 0);
                props.put("spring.datasource.url", url);
                props.put("spring.datasource.username", postgres.getUsername());
                props.put("spring.datasource.password", postgres.getPassword());
                props.put("jwt.secret", "this-is-a-very-long-secret-key-for-tests-only-must-be-32-bytes");
                props.put("demo.password", "startup-race-demo-password");
                props.put("management.metrics.use-global-registry", false);
                go.await();
                ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ChatPlatformApplication.class)
                        .profiles("integration").run(TwoInstanceSupport.asArgs(props)); // args: highest precedence
                if (!url.equals(ctx.getEnvironment().getProperty("spring.datasource.url"))) {
                    ctx.close();
                    throw new IllegalStateException("not on the race database");
                }
                return ctx;
            }));
        }
        go.countDown();
        List<ConfigurableApplicationContext> contexts = new ArrayList<>();
        try {
            for (Future<ConfigurableApplicationContext> f : starts) {
                contexts.add(f.get(150, TimeUnit.SECONDS)); // both started: neither failed on a race
            }
            try (Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 Statement st = c.createStatement()) {
                assertThat(count(st, "SELECT count(*) FROM flyway_schema_history WHERE success"))
                        .as("each migration applied once").isEqualTo(
                                count(st, "SELECT count(DISTINCT version) FROM flyway_schema_history"));
                assertThat(count(st, "SELECT count(*) FROM flyway_schema_history WHERE NOT success")).isZero();
                assertThat(count(st, "SELECT count(*) FROM users WHERE username = 'dox-bot'")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM users WHERE username IN ('demo1','demo2','demo3')"))
                        .isEqualTo(3);
                assertThat(count(st, "SELECT count(*) FROM conversations WHERE title = 'Demo room'")).isEqualTo(1);
            }
        } finally {
            contexts.forEach(ConfigurableApplicationContext::close);
            pool.shutdownNow();
        }
    }

    /** A deterministic race: two threads seed the same new bot account at the same moment, 25 times. */
    @Test
    void seedingTheSameAccountFromTwoThreadsNeverFailsAndCreatesOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 25; i++) {
                String name = "seedrace" + UUID.randomUUID().toString().substring(0, 8);
                CountDownLatch go = new CountDownLatch(1);
                Future<UUID> a = pool.submit(() -> { go.await(); return userService.ensureBot(name, "Race").id(); });
                Future<UUID> b = pool.submit(() -> { go.await(); return userService.ensureBot(name, "Race").id(); });
                go.countDown();
                assertThat(a.get(20, TimeUnit.SECONDS)).as("round " + i).isEqualTo(b.get(20, TimeUnit.SECONDS));
                assertThat(jdbc.sql("SELECT count(*) FROM users WHERE username = :u").param("u", name)
                        .query(Long.class).single()).isEqualTo(1L);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
