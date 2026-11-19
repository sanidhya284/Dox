package com.chatplatform.integration;

import com.chatplatform.guest.AccountRemovalService;
import com.fasterxml.jackson.databind.JsonNode;
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
 * ADR-0012 Decision 8 with two instances (ADR-0013): both instances run the guest cleanup at the same moment.
 * Each expired guest is removed exactly once (the two runs' counts add up to the number of expired guests; every
 * guest is anonymized once), and every guest's socket closes with 4008 whichever instance it was on.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class GuestCleanupTwoInstanceTest extends TwoInstanceSupport {

    private static final int GUESTS = 30;
    private static final int WITH_SOCKETS = 6;

    @Autowired
    private AccountRemovalService removalA;

    @Autowired
    private JdbcClient jdbc;

    private long expiredLiveGuests() {
        return jdbc.sql("SELECT count(*) FROM users WHERE is_guest AND deleted_at IS NULL "
                + "AND created_at < now() - interval '24 hours'").query(Long.class).single();
    }

    @Test
    void bothInstancesCleaningAtOnceRemoveEachExpiredGuestExactlyOnce() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < GUESTS; i++) {
            ResponseEntity<String> r = (i % 2 == 0 ? restA : restB).postForEntity("/api/auth/guest",
                    AuthCookies.request(null, AuthCookies.ORIGIN), String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode body = json.readTree(r.getBody());
            ids.add(body.get("user").get("id").asText());
        }
        List<WsTestClient> sockets = new ArrayList<>();
        for (int i = 0; i < WITH_SOCKETS; i++) {
            sockets.add(socket(i % 2 == 0 ? portA : portB, ids.get(i)));
        }
        long leftovers = expiredLiveGuests(); // other tests' aged guests in the shared database
        jdbc.sql("UPDATE users SET created_at = now() - interval '25 hours' WHERE id IN (:ids)")
                .param("ids", ids.stream().map(UUID::fromString).toList()).update();
        long expected = leftovers + GUESTS;
        assertThat(expiredLiveGuests()).isEqualTo(expected);

        AccountRemovalService removalB = instanceB.getBean(AccountRemovalService.class);
        CountDownLatch go = new CountDownLatch(1); // created before the tasks are submitted
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int a;
        int b;
        try {
            Future<Integer> fa = pool.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                return removalA.removeExpiredGuests();
            });
            Future<Integer> fb = pool.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                return removalB.removeExpiredGuests();
            });
            go.countDown();
            a = fa.get(60, TimeUnit.SECONDS);
            b = fb.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        System.out.println("GuestCleanupTwoInstanceTest: A removed " + a + ", B removed " + b + " (expected "
                + expected + " in total)");

        assertThat(a + b).as("each expired guest processed exactly once, across both instances").isEqualTo(expected);
        assertThat(expiredLiveGuests()).isZero();
        long anonymized = jdbc.sql("SELECT count(*) FROM users WHERE id IN (:ids) AND deleted_at IS NOT NULL "
                        + "AND display_name = 'Deleted guest'")
                .param("ids", ids.stream().map(UUID::fromString).toList()).query(Long.class).single();
        assertThat(anonymized).isEqualTo(GUESTS);
        for (WsTestClient s : sockets) {
            assertThat(s.closed.get(5, TimeUnit.SECONDS).getCode()).as("4008 on either instance").isEqualTo(4008);
        }
    }
}
