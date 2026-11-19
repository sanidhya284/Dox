package com.chatplatform.integration;

import com.chatplatform.realtime.websocket.SharedSocketCap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ADR-0013 Decision 6 (amendment 3): the shared socket cap's entries carry an expiry that each heartbeat sweep
 * refreshes. Live sockets therefore keep counting for as long as they are open, and when an instance dies without
 * cleaning up, its entries stop counting within about timeout + interval (here 3 s + 1 s; in production 60 s + 25 s).
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
@TestPropertySource(properties = {"ratelimit.store=redis", "ws.max-sockets-per-user=2",
        "ws.heartbeat.interval=PT1S", "ws.heartbeat.timeout=PT3S"})
class SocketCapCrashTest extends TwoInstanceSupport {

    @Override
    protected Map<String, Object> extraPropertiesB() {
        return Map.of("ratelimit.store", "redis", "ws.max-sockets-per-user", 2,
                "ws.heartbeat.interval", "PT1S", "ws.heartbeat.timeout", "PT3S");
    }

    private boolean admittedOnA(String userId) throws Exception {
        WsTestClient c = socket(portA, userId);
        Thread.sleep(300); // a rejected socket is closed right after the upgrade
        boolean open = c.isOpen();
        c.close();
        if (open) {
            c.closed.get(5, TimeUnit.SECONDS);
            Thread.sleep(100); // unregistered before the next attempt
        }
        return open;
    }

    @Test
    void liveSocketsKeepCountingAndACrashedInstancesSocketsExpire() throws Exception {
        String[] u = newUser(restA, "cr");
        WsTestClient b1 = socket(portB, u[0]);
        WsTestClient b2 = socket(portB, u[0]);
        Thread.sleep(300);
        assertThat(b1.isOpen() && b2.isOpen()).isTrue();
        assertThat(admittedOnA(u[0])).as("cap 2 reached on B").isFalse();

        Thread.sleep(2 * 3_000); // twice the heartbeat timeout: without the sweep's refresh the entries would expire
        assertThat(b1.isOpen() && b2.isOpen()).isTrue();
        assertThat(admittedOnA(u[0])).as("live sockets keep counting (refreshed by B's sweep)").isFalse();

        // B "crashes": from now on it neither refreshes nor removes its entries; then it goes away.
        instanceB.getBean(SharedSocketCap.class).simulateCrash();
        instanceB.close();
        assertThat(admittedOnA(u[0])).as("the dead instance's entries still count right after the crash").isFalse();

        long crashed = System.nanoTime();
        await("the crashed instance's entries to expire").atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500)).until(() -> admittedOnA(u[0]));
        long ms = (System.nanoTime() - crashed) / 1_000_000;
        System.out.println("SocketCapCrashTest: crashed instance's slots freed after " + ms + " ms");
        assertThat(ms).as("within about timeout + interval").isLessThanOrEqualTo(3_000 + 1_000 + 1_500);
    }
}
