package com.chatplatform.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0013 Decision 8: on shutdown the instance first DRAINS (readiness 503 so the load balancer stops sending,
 * while it keeps serving), then shuts down gracefully (a request already in flight finishes). An instance with a
 * test-only slow endpoint is stopped while a 6 s request is running; drain 3 s.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ShutdownDrainTest extends AbstractIntegrationTest {

    /** Test-only: a request that takes {@code ms} to answer. */
    @RestController
    static class SlowController {
        @GetMapping("/api/test/slow")
        String slow(@RequestParam long ms) throws InterruptedException {
            Thread.sleep(ms);
            return "done";
        }
    }

    @Test
    void readinessGoesDownForTheDrainWhileServingAndAnInFlightRequestFinishes() throws Exception {
        ConfigurableApplicationContext c = TwoInstanceSupport.startInstance(Map.of(
                "realtime.bus", "local",
                "app.shutdown.drain", "PT3S",
                "server.shutdown", "graceful",
                "spring.lifecycle.timeout-per-shutdown-phase", "20s"), SlowController.class);
        int port = ((WebServerApplicationContext) c).getWebServer().getPort();
        TestRestTemplate rest = TwoInstanceSupport.jdkRest(port);
        String name = "dr" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<String> reg = rest.postForEntity("/api/auth/register", Map.of("username", name,
                "email", name + "@example.com", "password", "password123", "displayName", name), String.class);
        assertThat(reg.getStatusCode().value()).isEqualTo(201);
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(reg.getBody())
                .get("accessToken").asText();
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(token);

        ResponseEntity<String> before = rest.getForEntity("/actuator/health", String.class);
        assertThat(before.getStatusCode().value()).isEqualTo(200);
        assertThat(before.getBody()).as("the public body is unchanged: no groups, no details")
                .isEqualTo("{\"status\":\"UP\"}");
        assertThat(rest.getForEntity("/internal/ready", String.class).getStatusCode().value()).isEqualTo(200);

        CompletableFuture<ResponseEntity<String>> slow = CompletableFuture.supplyAsync(() -> rest.exchange(
                "/api/test/slow?ms=6000", HttpMethod.GET, new HttpEntity<>(auth), String.class));
        Thread.sleep(300); // the slow request is in flight
        long stopAt = System.nanoTime();
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(c::close); // what SIGTERM triggers
        Thread.sleep(700);

        // Draining: readiness down, liveness up, normal requests still served.
        assertThat(rest.getForEntity("/internal/ready", String.class).getStatusCode().value())
                .as("the load balancer's check during the drain").isEqualTo(503);
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value())
                .as("public health stays 200 during a planned drain").isEqualTo(200);
        assertThat(rest.exchange("/api/conversations", HttpMethod.GET, new HttpEntity<>(auth), String.class)
                .getStatusCode().value()).as("still serving during the drain").isEqualTo(200);

        ResponseEntity<String> done = slow.get(30, TimeUnit.SECONDS);
        assertThat(done.getStatusCode().value()).as("the in-flight request finished").isEqualTo(200);
        assertThat(done.getBody()).isEqualTo("done");
        stopping.get(30, TimeUnit.SECONDS);
        long stopMs = (System.nanoTime() - stopAt) / 1_000_000;
        System.out.println("ShutdownDrainTest: stop took " + stopMs + " ms (drain 3000 ms, then graceful)");
        assertThat(stopMs).as("the drain ran before the graceful shutdown").isGreaterThanOrEqualTo(3_000);
    }
}
