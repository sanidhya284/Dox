package com.chatplatform.integration;

import com.chatplatform.shared.redis.RedisGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ADR-0013 Decision 5: Redis is a delivery and coordination optimization only. With Redis paused, restarted or
 * absent, the app stays healthy and every send is acked and stored; only cross-instance pushes are lost (clients
 * recover from history). A PAUSED Redis keeps its TCP connection open, so the client sees command timeouts, not a
 * disconnect: the breaker must open on those.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
@TestPropertySource(properties = {"ratelimit.store=redis", "ws.heartbeat.interval=PT1S", "ws.heartbeat.timeout=PT4S"})
class RedisOutageTest extends TwoInstanceSupport {

    private static final int RATE_PER_SECOND = 20;

    @Autowired
    private RedisGuard guardA;

    @Autowired
    private StringRedisTemplate redisA;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry metersA;

    private volatile boolean paused;

    @Override
    protected Map<String, Object> extraPropertiesB() {
        return Map.of("ratelimit.store", "redis", "ws.heartbeat.interval", "PT1S", "ws.heartbeat.timeout", "PT4S");
    }

    @AfterEach
    void unpause() {
        if (paused) {
            DockerClientFactory.instance().client().unpauseContainerCmd(redis.getContainerId()).exec();
            paused = false;
        }
    }

    private void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(redis.getContainerId()).exec();
        paused = true;
    }

    /** Waits until Redis works end to end: a send on A is pushed to a socket on B. */
    private void awaitCrossInstancePushes(String senderToken, String conv, WsTestClient onB) {
        await("cross-instance pushes").atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    String body = "probe-" + UUID.randomUUID();
                    assertThat(send(restA, senderToken, conv, body)).isEqualTo(201);
                    long end = System.nanoTime() + 1_500_000_000L;
                    boolean seen = false;
                    while (!seen && System.nanoTime() < end) {
                        String f = onB.next(100, TimeUnit.MILLISECONDS);
                        seen = f != null && f.contains(body);
                    }
                    assertThat(seen).isTrue();
                });
    }

    private long attemptsA() {
        long n = 0;
        for (RedisGuard.Op op : RedisGuard.Op.values()) {
            n += guardA.attempts(op);
        }
        return n;
    }

    private double pushDroppedA() {
        return metersA.get("chat.push.dropped").counter().count();
    }

    /** Sends {@code count} REST messages from A at a steady rate; returns local push latencies in ms. */
    private long[] steadySend(String token, String conv, WsTestClient localRecipient, int count, String tag)
            throws Exception {
        Map<String, Long> sentAt = new HashMap<>();
        long period = 1_000_000_000L / RATE_PER_SECOND;
        long next = System.nanoTime();
        for (int i = 0; i < count; i++) {
            String body = tag + "-" + i;
            long now = System.nanoTime();
            if (next > now) {
                TimeUnit.NANOSECONDS.sleep(next - now);
            }
            next += period;
            sentAt.put(body, System.nanoTime());
            assertThat(send(restA, token, conv, body)).as("every send is acked and stored").isEqualTo(201);
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> localRecipient.arrivals.stream()
                .filter(a -> a.payload().contains("\"" + tag + "-")).count() >= count);
        List<Long> ms = new ArrayList<>();
        for (WsTestClient.Arrival a : localRecipient.arrivals) {
            JsonNode n = json.readTree(a.payload());
            if (!"message".equals(n.get("type").asText())) {
                continue;
            }
            Long t0 = sentAt.get(n.get("message").get("body").asText());
            if (t0 != null) {
                ms.add((a.nanos() - t0) / 1_000_000);
            }
        }
        assertThat(ms).as("every local push arrived exactly once").hasSize(count);
        return ms.stream().mapToLong(Long::longValue).sorted().toArray();
    }

    private static long pct(long[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
    }

    @Test
    void pausedRedisOpensTheBreakerOnTimeoutsAndLocalDeliveryStaysAtBaseline() throws Exception {
        String[] alice = newUser(restA, "pa");
        String[] cara = newUser(restA, "pc");
        String[] bob = newUser(restA, "pb");
        String conv = group(restA, alice[1], cara[0], bob[0]);
        WsTestClient caraOnA = socket(portA, cara[0]);   // a LOCAL recipient of A's sends
        WsTestClient bobOnB = socket(portB, bob[0]);     // a recipient on the other instance
        awaitCrossInstancePushes(alice[1], conv, bobOnB);

        long[] baseline = steadySend(alice[1], conv, caraOnA, 5 * RATE_PER_SECOND, "base");
        double droppedBefore = pushDroppedA();

        pause();
        assertThat(restA.getForEntity("/actuator/health", String.class).getBody()).contains("\"UP\"");

        // The first limiter call waits for the 200 ms command timeout and opens the breaker.
        assertThat(send(restA, alice[1], conv, "trips the breaker")).isEqualTo(201);
        assertThat(guardA.isOpen()).as("a command timeout opened the breaker").isTrue();

        // While open: limiter calls make 0 Redis attempts and add no timeout wait.
        long attemptsBefore = attemptsA();
        long start = System.nanoTime();
        long slowest = 0;
        for (int i = 0; i < 20; i++) {
            long t = System.nanoTime();
            assertThat(send(restA, alice[1], conv, "open-" + i)).isEqualTo(201);
            slowest = Math.max(slowest, (System.nanoTime() - t) / 1_000_000);
        }
        assertThat((System.nanoTime() - start) / 1_000_000).as("still inside the 5 s open period").isLessThan(4_000);
        assertThat(attemptsA() - attemptsBefore).as("Redis attempts while open").isZero();
        assertThat(slowest).as("no send waited for the 200 ms command timeout").isLessThan(200);

        // Login, a ticket and a new socket work on the fallbacks.
        assertThat(restB.postForEntity("/api/auth/login",
                new org.springframework.http.HttpEntity<>(Map.of("username", alice[2], "password", "password123")),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(restB, "/api/ws/ticket", alice[1], null).getStatusCode().value()).isEqualTo(200);
        WsTestClient extraOnB = socket(portB, alice[0]);
        Thread.sleep(300);
        assertThat(extraOnB.isOpen()).as("the socket cap falls back to the local cap").isTrue();

        // 30 s of steady sending while paused: local pushes stay at baseline, none rejected, Redis attempts bounded.
        long attemptsBeforeRun = attemptsA();
        bobOnB.frames.clear();
        long[] during = steadySend(alice[1], conv, caraOnA, 30 * RATE_PER_SECOND, "paused");
        long attemptsDuringRun = attemptsA() - attemptsBeforeRun;
        System.out.printf("RedisOutageTest local push latency ms: baseline p50=%d p95=%d max=%d (n=%d); "
                        + "paused p50=%d p95=%d max=%d (n=%d); Redis attempts during 30 s paused=%d%n",
                pct(baseline, 0.5), pct(baseline, 0.95), baseline[baseline.length - 1], baseline.length,
                pct(during, 0.5), pct(during, 0.95), during[during.length - 1], during.length, attemptsDuringRun);
        assertThat(pct(during, 0.5)).as("p50 at baseline").isLessThanOrEqualTo(pct(baseline, 0.5) + 20);
        assertThat(pct(during, 0.95)).as("p95 at baseline").isLessThanOrEqualTo(pct(baseline, 0.95) + 50);
        assertThat(pushDroppedA() - droppedBefore).as("no local push rejected").isZero();
        // No queueing: one probe per 5 s open period per caller kind, never one command per send (600 sends).
        assertThat(attemptsDuringRun).as("Redis commands attempted during the 30 s pause").isLessThanOrEqualTo(30);

        // B got none of it as a push while paused, but has all of it in history (clients recover from there).
        assertThat(bobOnB.frames.stream().filter(f -> f.contains("\"paused-")).count()).isZero();
        JsonNode history = json.readTree(get(restB, "/api/conversations/" + conv
                + "/messages?after=0&limit=100", bob[1]).getBody());
        assertThat(history.get("messages").size()).isPositive();

        unpause();
        awaitCrossInstancePushes(alice[1], conv, bobOnB);
        assertThat(guardA.isOpen()).isFalse();
        caraOnA.close();
        bobOnB.close();
        extraOnB.close();
    }

    @Test
    void afterAFullRedisRestartPushesResumeAndTheSocketCapRebuilds() throws Exception {
        String[] alice = newUser(restA, "ra");
        String[] bob = newUser(restA, "rb");
        String conv = group(restA, alice[1], bob[0]);
        WsTestClient aliceOnA = socket(portA, alice[0]);
        WsTestClient bobOnB = socket(portB, bob[0]);
        awaitCrossInstancePushes(alice[1], conv, bobOnB);
        String capKey = "chat:sockets:" + alice[0];
        await().atMost(Duration.ofSeconds(10)).until(() -> Long.valueOf(1).equals(redisA.opsForZSet().zCard(capKey)));

        // Restart: no persistence, so every key is gone. Sends keep working throughout.
        Thread restart = new Thread(() ->
                DockerClientFactory.instance().client().restartContainerCmd(redis.getContainerId()).exec());
        restart.start();
        int sends = 0;
        while (restart.isAlive() || sends < 10) {
            assertThat(send(restA, alice[1], conv, "during-restart-" + sends)).isEqualTo(201);
            sends++;
            Thread.sleep(100);
        }
        restart.join();

        awaitCrossInstancePushes(alice[1], conv, bobOnB);
        // The heartbeat sweep re-adds open sessions with plain ZADD: the cap's set is rebuilt from empty.
        await("socket cap rebuilt").atMost(Duration.ofSeconds(15)).ignoreExceptions()
                .until(() -> Long.valueOf(1).equals(redisA.opsForZSet().zCard(capKey)));
        assertThat(redisA.getExpire(capKey, TimeUnit.MILLISECONDS)).as("the key expires with its largest score")
                .isBetween(1L, 4_000L);
        // Limits rebuild from empty (a documented reset): the next send creates a fresh window.
        assertThat(send(restA, alice[1], conv, "after-restart")).isEqualTo(201);
        assertThat(redisA.hasKey("rl:send:" + alice[0])).isTrue();
        aliceOnA.close();
        bobOnB.close();
    }

    @Test
    void withNoRedisAtAllAnInstanceIsHealthyAndFullyUsable() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        ConfigurableApplicationContext c = startInstance(Map.of("spring.data.redis.port", deadPort,
                "ratelimit.store", "redis"));
        try {
            int port = ((WebServerApplicationContext) c).getWebServer().getPort();
            TestRestTemplate rest = jdkRest(port);
            ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
            assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(health.getBody()).contains("\"UP\"");
            String[] alice = newUser(rest, "na");
            String[] bob = newUser(rest, "nb");
            String conv = group(rest, alice[1], bob[0]);
            assertThat(send(rest, alice[1], conv, "no redis")).isEqualTo(201);
            assertThat(rest.postForEntity("/api/auth/login",
                    new org.springframework.http.HttpEntity<>(Map.of("username", alice[2], "password", "password123")),
                    String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            WsTestClient ws = socket(port, bob[0]);
            Thread.sleep(300);
            assertThat(ws.isOpen()).isTrue();
            assertThat(send(rest, alice[1], conv, "pushed locally")).isEqualTo(201);
            await().atMost(Duration.ofSeconds(5)).until(() -> ws.frames.stream()
                    .anyMatch(f -> f.contains("pushed locally")));
            ws.close();
        } finally {
            c.close();
        }
    }
}
