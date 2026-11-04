package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RefreshRequest;
import com.chatplatform.auth.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many concurrent replays of one refresh token must never deadlock.
 *
 * <p>Each round: a fresh user, {@value #PRIOR_ROTATIONS} ordinary sequential refreshes (so the family
 * has several rows), then {@value #THREADS} simultaneous refreshes of the current token.
 * Contract per round: exactly one 200, the rest 401, never a 500, and the whole family
 * (including the token the winner just received) revoked. Every round is checked and the
 * summary is printed before asserting, so a run reports its failure rate, not just the first failure.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
public class RefreshReplayDeadlockTest extends AbstractIntegrationTest {

    private static final int ROUNDS = 30;
    private static final int THREADS = 8;
    /** Sequential rotations before the burst: the family then has several rows for revokeFamily to lock. */
    private static final int PRIOR_ROTATIONS = 5;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void concurrentReplaysOfOneRefreshTokenNeverDeadlockAcrossManyRounds() throws Exception {
        List<String> badRounds = new ArrayList<>();
        ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                String problem = runRound(exec);
                if (problem != null) {
                    badRounds.add("round " + round + ": " + problem);
                }
            }
        } finally {
            exec.shutdownNow();
        }

        System.out.println("REFRESH_LOOP bad=" + badRounds.size() + "/" + ROUNDS + " " + badRounds);
        assertThat(badRounds).as("rounds violating the refresh contract").isEmpty();
    }

    /** Returns null if the round met the contract, otherwise a description of what went wrong. */
    private String runRound(ExecutorService exec) throws Exception {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> reg = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("rd_" + s, "rd_" + s + "@example.com", "password123", "RD " + s),
                AuthResponse.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID userId = reg.getBody().user().id();
        String refreshToken = reg.getBody().refreshToken();
        for (int i = 0; i < PRIOR_ROTATIONS; i++) {
            ResponseEntity<AuthResponse> rot = restTemplate.postForEntity(
                    "/api/auth/refresh", new RefreshRequest(refreshToken), AuthResponse.class);
            assertThat(rot.getStatusCode()).isEqualTo(HttpStatus.OK);
            refreshToken = rot.getBody().refreshToken();
        }
        String contested = refreshToken;

        // Latches exist before any task is submitted; tasks are collected with get(timeout).
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(exec.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                ResponseEntity<String> r = restTemplate.postForEntity(
                        "/api/auth/refresh", new RefreshRequest(contested), String.class);
                return HttpStatus.valueOf(r.getStatusCode().value());
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int ok = 0, unauthorized = 0;
        List<HttpStatus> other = new ArrayList<>();
        for (Future<HttpStatus> f : futures) {
            HttpStatus st = f.get(30, TimeUnit.SECONDS);
            if (st == HttpStatus.OK) ok++;
            else if (st == HttpStatus.UNAUTHORIZED) unauthorized++;
            else other.add(st);
        }

        long active = jdbcClient.sql("SELECT count(*) FROM refresh_tokens WHERE user_id = :u AND revoked_at IS NULL")
                .param("u", userId).query(Long.class).single();

        if (ok == 1 && unauthorized == THREADS - 1 && other.isEmpty() && active == 0) {
            return null;
        }
        return "200x" + ok + " 401x" + unauthorized + " other=" + other + " activeTokensLeft=" + active;
    }
}
