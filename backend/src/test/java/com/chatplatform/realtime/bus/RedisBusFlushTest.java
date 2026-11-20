package com.chatplatform.realtime.bus;

import com.chatplatform.shared.metrics.ChatMetrics;
import com.chatplatform.shared.redis.RedisGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0013 (the admin runner's flush, runbook 9a): with Redis unreachable, flush returns within its bound and says
 * the publish did NOT go out, so the runner can say the close was lost and the 15-minute window applies.
 */
class RedisBusFlushTest {

    @Test
    void withRedisDownFlushIsBoundedAndReportsTheLoss() throws IOException {
        RedisProperties props = new RedisProperties();
        props.setHost("localhost");
        props.setPort(closedPort());
        props.setTimeout(Duration.ofMillis(200));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RedisBus bus = new RedisBus(props, new ObjectMapper(), new ChatMetrics(meters),
                new RedisGuard(Duration.ofSeconds(5), System::currentTimeMillis, meters), 100);
        try {
            bus.publish(Envelope.closeUser(bus.instanceId(), UUID.randomUUID(), 4002, "credentials revoked"));
            long start = System.nanoTime();
            boolean delivered = bus.flush(Duration.ofSeconds(5));
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertThat(delivered).as("the close did not go out").isFalse();
            assertThat(ms).as("bounded: returns well within the timeout").isLessThan(5_500);
        } finally {
            bus.destroy();
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort(); // closed again when this returns: nothing listens there
        }
    }
}
