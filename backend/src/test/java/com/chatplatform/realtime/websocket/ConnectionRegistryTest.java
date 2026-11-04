package com.chatplatform.realtime.websocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ConnectionRegistryTest {

    private final ConnectionRegistry registry = new ConnectionRegistry();

    private static WebSocketSession session(String id) {
        // stubOnly: no invocation recording, so the concurrent test does not pile up memory.
        WebSocketSession s = mock(WebSocketSession.class, withSettings().stubOnly());
        when(s.getId()).thenReturn(id);
        return s;
    }

    @Test
    void keepsSeveralSessionsPerUserAndRemovesOnlyTheOneUnregistered() {
        UUID user = UUID.randomUUID();
        WebSocketSession a = session("a");
        WebSocketSession b = session("b");

        registry.register(user, a);
        registry.register(user, b);
        assertThat(registry.sessionIdsFor(user)).containsExactlyInAnyOrder("a", "b");

        registry.unregister(user, a);
        assertThat(registry.sessionIdsFor(user)).containsExactly("b");
        assertThat(registry.hasEntry(user)).isTrue();
    }

    @Test
    void lastUnregisterRemovesTheUserEntry() {
        UUID user = UUID.randomUUID();
        WebSocketSession a = session("a");

        registry.register(user, a);
        registry.unregister(user, a);

        assertThat(registry.sessionIdsFor(user)).isEmpty();
        assertThat(registry.hasEntry(user)).as("no empty entry left behind").isFalse();
    }

    @Test
    void unregisteringUnknownSessionOrUserIsANoOp() {
        UUID user = UUID.randomUUID();
        registry.unregister(user, session("x"));
        assertThat(registry.hasEntry(user)).isFalse();

        registry.register(user, session("a"));
        registry.unregister(user, session("not-registered"));
        assertThat(registry.sessionIdsFor(user)).containsExactly("a");
    }

    @Test
    void closeAllClosesOnlyThatUsersSessionsAndKeepsGoingWhenOneCloseFails() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        WebSocketSession a1 = mock(WebSocketSession.class);
        WebSocketSession a2 = mock(WebSocketSession.class);
        WebSocketSession a3 = mock(WebSocketSession.class);
        WebSocketSession b1 = mock(WebSocketSession.class);
        doThrow(new IOException("peer gone")).when(a1).close(CloseStatus.POLICY_VIOLATION);
        doThrow(new IllegalStateException("already closing")).when(a2).close(CloseStatus.POLICY_VIOLATION);
        registry.register(alice, a1);
        registry.register(alice, a2);
        registry.register(alice, a3);
        registry.register(bob, b1);

        registry.closeAll(alice, CloseStatus.POLICY_VIOLATION);

        verify(a1).close(CloseStatus.POLICY_VIOLATION);
        verify(a2).close(CloseStatus.POLICY_VIOLATION);
        verify(a3).close(CloseStatus.POLICY_VIOLATION);
        verify(b1, org.mockito.Mockito.never()).close(org.mockito.ArgumentMatchers.any());
    }

    /**
     * Many threads churn register/unregister for ONE user, so the user's entry keeps going from
     * empty to non-empty and back. A two-step "add, then later remove the key if empty" loses
     * sessions here: the key is removed after another thread has added to that same set.
     * Every register must be visible immediately, and at the end the registry must hold exactly
     * the sessions that were registered and never unregistered.
     */
    @Test
    void concurrentRegisterAndUnregisterForOneUserNeverLosesASession() throws Exception {
        UUID user = UUID.randomUUID();
        int threads = 4;
        int iterations = 200_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        Set<String> expectedKept = new HashSet<>();

        try {
            for (int t = 0; t < threads; t++) {
                WebSocketSession churn = session("churn-" + t);
                WebSocketSession keep = session("keep-" + t);
                expectedKept.add("keep-" + t);
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    int lost = 0;
                    for (int i = 0; i < iterations; i++) {
                        registry.register(user, churn);
                        if (!registry.sessionIdsFor(user).contains(churn.getId())) {
                            lost++;
                        }
                        registry.unregister(user, churn);
                    }
                    registry.register(user, keep);
                    return lost;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int lostTotal = 0;
            for (Future<Integer> f : results) {
                lostTotal += f.get(60, TimeUnit.SECONDS);
            }

            assertThat(lostTotal).as("registers that were not visible right after register()").isZero();
            assertThat(registry.sessionIdsFor(user))
                    .as("exactly the sessions registered and not unregistered")
                    .containsExactlyInAnyOrderElementsOf(expectedKept);
        } finally {
            pool.shutdownNow();
        }
    }
}
