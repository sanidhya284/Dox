package com.chatplatform.realtime.websocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 2c: every send goes through a per-session decorator; a slow consumer is closed with 4003. */
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class ConnectionRegistrySendTest {

    private static WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(new HashMap<>());
        return s;
    }

    private static TextMessage text(String s) {
        return new TextMessage(s);
    }

    @Test
    void sendToUsersReachesEverySessionExceptTheExcludedOne() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry(5000, 512 * 1024);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        WebSocketSession a1 = session("a1");
        WebSocketSession a2 = session("a2");
        WebSocketSession b1 = session("b1");
        registry.register(alice, a1);
        registry.register(alice, a2);
        registry.register(bob, b1);

        registry.sendToUsers(List.of(alice, bob), text("m"), "a1");

        verify(a1, never()).sendMessage(any());
        verify(a2).sendMessage(text("m"));
        verify(b1).sendMessage(text("m"));
    }

    @Test
    void sendToReachesOnlyThatSession() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry(5000, 512 * 1024);
        UUID alice = UUID.randomUUID();
        WebSocketSession a1 = session("a1");
        WebSocketSession a2 = session("a2");
        registry.register(alice, a1);
        registry.register(alice, a2);

        assertThat(registry.sendTo(alice, "a2", text("ack"))).isTrue();
        assertThat(registry.sendTo(alice, "nope", text("ack"))).isFalse();

        verify(a2).sendMessage(text("ack"));
        verify(a1, never()).sendMessage(any());
    }

    /**
     * A session whose write blocks (stalled peer) holds the decorator's flush lock. The next send
     * finds the send-time limit exceeded: the registry must close that session with 4003 (not the
     * decorator's own 4500 rewrite), unregister it, and keep delivering to everyone else.
     */
    @Test
    void slowConsumerIsClosedWith4003AndUnregisteredWhileOthersStillReceive() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry(100, 512 * 1024); // 100 ms send-time limit
        UUID slowUser = UUID.randomUUID();
        UUID fastUser = UUID.randomUUID();
        WebSocketSession slow = session("slow");
        WebSocketSession fast = session("fast");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch slowWriteStarted = new CountDownLatch(1);
        AtomicInteger fastReceived = new AtomicInteger();
        doAnswer(inv -> {
            slowWriteStarted.countDown();
            release.await(30, TimeUnit.SECONDS); // stalled peer
            return null;
        }).when(slow).sendMessage(any(WebSocketMessage.class));
        doAnswer(inv -> fastReceived.incrementAndGet()).when(fast).sendMessage(any(WebSocketMessage.class));
        registry.register(slowUser, slow);
        registry.register(fastUser, fast);

        ExecutorService pusher = Executors.newSingleThreadExecutor();
        try {
            // First push: its write to the slow session blocks inside the decorator.
            Future<?> first = pusher.submit(() -> registry.sendToUsers(List.of(slowUser, fastUser), text("m1"), null));
            assertThat(slowWriteStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(250); // exceed the 100 ms send-time limit

            // Second push, from another thread: the slow session's decorator trips.
            registry.sendToUsers(List.of(slowUser, fastUser), text("m2"), null);

            verify(slow).close(WsCloseStatuses.SLOW_CONSUMER);
            assertThat(WsCloseStatuses.SLOW_CONSUMER.getCode()).isEqualTo(4003);
            assertThat(registry.hasEntry(slowUser)).as("slow consumer unregistered").isFalse();
            assertThat(registry.slowConsumerCloses()).as("counted for the INFO/WARN totals").isEqualTo(1);

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(fastReceived.get()).as("the other recipient got both messages").isEqualTo(2);
        } finally {
            release.countDown();
            pusher.shutdownNow();
        }
        verify(slow, never()).close(CloseStatus.SESSION_NOT_RELIABLE);
    }
}
