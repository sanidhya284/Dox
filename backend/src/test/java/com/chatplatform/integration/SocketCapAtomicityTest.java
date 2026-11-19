package com.chatplatform.integration;

import com.chatplatform.realtime.websocket.SharedSocketCap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0013 Decision 6: the socket cap's check and add are atomic ACROSS instances. Handshakes for one user on both
 * instances are held at the admission point (a latch every one of them must reach, set on both instances' caps)
 * and then released together, so they all race on the same Redis state. With the single Lua script exactly
 * {@code cap} are admitted; a two-step check-then-add lets them all through (the mutation in the step's report).
 */
@Timeout(value = 6, unit = TimeUnit.MINUTES)
@TestPropertySource(properties = {"ratelimit.store=redis", "ws.max-sockets-per-user=10"})
class SocketCapAtomicityTest extends TwoInstanceSupport {

    private static final int CAP = 10;

    @Autowired
    private SharedSocketCap capA;

    private final List<WsTestClient> opened = new ArrayList<>();

    @Override
    protected Map<String, Object> extraPropertiesB() {
        return Map.of("ratelimit.store", "redis", "ws.max-sockets-per-user", CAP);
    }

    @AfterEach
    void cleanUp() throws Exception {
        setHook(null);
        for (WsTestClient c : opened) {
            c.close();
        }
        opened.clear();
    }

    private void setHook(Consumer<UUID> hook) {
        capA.setAdmissionHook(hook);
        instanceB.getBean(SharedSocketCap.class).setAdmissionHook(hook);
    }

    /** Holds every admission of {@code user} until {@code parties} of them have reached the check/add point. */
    private CountDownLatch holdUntilAllArrive(UUID user, int parties) {
        CountDownLatch arrived = new CountDownLatch(parties);
        setHook(id -> {
            if (!id.equals(user)) {
                return;
            }
            arrived.countDown();
            try {
                arrived.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return arrived;
    }

    /** Opens {@code n} sockets at once, alternating instances; returns how many stayed open and how many got 4007. */
    private int[] simultaneous(String userId, int n) throws Exception {
        UUID user = UUID.fromString(userId);
        CountDownLatch arrived = holdUntilAllArrive(user, n); // latch exists before any task is submitted
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<WsTestClient> clients = new ArrayList<>();
        try {
            List<Future<WsTestClient>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int port = i % 2 == 0 ? portA : portB;
                futures.add(pool.submit(() -> socket(port, userId)));
            }
            for (Future<WsTestClient> f : futures) {
                clients.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        opened.addAll(clients);
        // The server admits a socket just after the 101 upgrade, so the client's connect can return first.
        assertThat(arrived.await(10, TimeUnit.SECONDS)).as("every handshake reached the admission point together")
                .isTrue();
        Thread.sleep(500); // a rejected socket is closed right after the upgrade
        int open = 0;
        int capped = 0;
        for (WsTestClient c : clients) {
            if (c.isOpen()) {
                open++;
            } else if (c.closed.isDone() && c.closed.get().getCode() == 4007) {
                capped++;
            }
        }
        return new int[]{open, capped};
    }

    @RepeatedTest(20)
    void atNineSocketsTwoSimultaneousHandshakesOnDifferentInstancesAdmitExactlyOne() throws Exception {
        String user = newUser(restA, "at")[0];
        for (int i = 0; i < CAP - 1; i++) {
            opened.add(socket(i % 2 == 0 ? portA : portB, user));
        }
        Thread.sleep(300);
        int[] r = simultaneous(user, 2);
        assertThat(r).as("[open, closed 4007]").containsExactly(1, 1);
    }

    @RepeatedTest(20)
    void twentySimultaneousHandshakesSplitAcrossInstancesAdmitExactlyTheCap() throws Exception {
        String user = newUser(restA, "bu")[0];
        int[] r = simultaneous(user, 2 * CAP);
        assertThat(r).as("[open, closed 4007]").containsExactly(CAP, CAP);
    }
}
