package com.chatplatform.realtime.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Deterministic heartbeat logic: fake clock, real registry, mocked sessions. */
class HeartbeatMonitorTest {

    private static final Duration INTERVAL = Duration.ofSeconds(25);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final HeartbeatMonitor monitor =
            new HeartbeatMonitor(registry, mock(TaskScheduler.class), INTERVAL, TIMEOUT, clock::get);

    private static WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(new HashMap<>());
        return s;
    }

    private void advance(Duration d) {
        clock.addAndGet(d.toNanos());
    }

    private WebSocketSession connected(UUID user, String id) {
        WebSocketSession s = session(id);
        registry.register(user, s);
        monitor.track(user, id);
        return s;
    }

    @Test
    void freshSessionIsPingedNotClosed() throws Exception {
        UUID user = UUID.randomUUID();
        WebSocketSession s = connected(user, "s1");

        advance(INTERVAL);
        monitor.sweep();

        verify(s).sendMessage(isA(PingMessage.class));
        verify(s, never()).close(any());
        assertThat(monitor.pingsSent()).isEqualTo(1);
        assertThat(registry.hasEntry(user)).isTrue();
    }

    @Test
    void staleSessionIsClosedWith4006AndUnregistered() throws Exception {
        UUID user = UUID.randomUUID();
        WebSocketSession s = connected(user, "s1");

        advance(TIMEOUT.plusSeconds(1));
        monitor.sweep();

        verify(s).close(WsCloseStatuses.IDLE_TIMEOUT);
        assertThat(WsCloseStatuses.IDLE_TIMEOUT.getCode()).isEqualTo(4006);
        assertThat(registry.hasEntry(user)).isFalse();
        verify(s, never()).sendMessage(isA(PingMessage.class));
        assertThat(monitor.trackedSessions()).isZero();
    }

    @Test
    void pongKeepsTheSessionAlive() throws Exception {
        UUID user = UUID.randomUUID();
        WebSocketSession s = connected(user, "s1");

        advance(Duration.ofSeconds(50));
        monitor.onPong("s1");
        advance(Duration.ofSeconds(50)); // 100 s since connect, but only 50 s since the pong
        monitor.sweep();

        verify(s, never()).close(any());
        assertThat(monitor.pongsReceived()).isEqualTo(1);
    }

    @Test
    void inboundTextFrameKeepsTheSessionAlive() throws Exception {
        UUID user = UUID.randomUUID();
        WebSocketSession s = connected(user, "s1");

        advance(Duration.ofSeconds(50));
        monitor.touch("s1");
        advance(Duration.ofSeconds(50));
        monitor.sweep();

        verify(s, never()).close(any());
    }

    @Test
    void forgottenSessionIsNeitherPingedNorClosed() throws Exception {
        UUID user = UUID.randomUUID();
        WebSocketSession s = connected(user, "s1");
        monitor.forget("s1");

        advance(TIMEOUT.plusSeconds(1));
        monitor.sweep();

        verify(s, never()).close(any());
        verify(s, never()).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void timeoutMustBeAtLeastTwiceTheInterval() {
        TaskScheduler sch = mock(TaskScheduler.class);
        assertThatThrownBy(() -> new HeartbeatMonitor(registry, sch, Duration.ofSeconds(25), Duration.ofSeconds(49), System::nanoTime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ws.heartbeat.timeout");
        assertThatThrownBy(() -> new HeartbeatMonitor(registry, sch, Duration.ZERO, Duration.ofSeconds(60), System::nanoTime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ws.heartbeat.interval");
        assertThatThrownBy(() -> new HeartbeatMonitor(registry, sch, null, Duration.ofSeconds(60), System::nanoTime))
                .isInstanceOf(IllegalStateException.class);
        // exactly 2 x interval is accepted
        new HeartbeatMonitor(registry, sch, Duration.ofSeconds(25), Duration.ofSeconds(50), System::nanoTime);
        assertThat(TimeUnit.SECONDS.toNanos(1)).isPositive();
    }

    @Test
    void sweepPingsEverySessionOfEveryUser() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        WebSocketSession a1 = connected(a, "a1");
        WebSocketSession a2 = connected(a, "a2");
        WebSocketSession b1 = connected(b, "b1");

        monitor.sweep();
        monitor.sweep();

        verify(a1, times(2)).sendMessage(isA(PingMessage.class));
        verify(a2, times(2)).sendMessage(isA(PingMessage.class));
        verify(b1, times(2)).sendMessage(isA(PingMessage.class));
        assertThat(monitor.pingsSent()).isEqualTo(6);
    }
}
