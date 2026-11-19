package com.chatplatform.integration;

import com.chatplatform.realtime.bus.Envelope;
import com.chatplatform.realtime.bus.RealtimeBus;
import com.chatplatform.realtime.websocket.ConnectionRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * ADR-0013: the Phase 3 convergence test (same seeds, same faults, same assertions) with TWO instances. Clients
 * alternate between A and B, so most pushes cross over the Redis bus. Server-side faults are injected on A (dropped
 * pushes and acks, failed commits) plus 20% of A's bus publishes dropped (a lost publish is the bus's failure mode,
 * at most once); the server closer closes sessions on both instances. In the tail phase every push AND every
 * publish is dropped, so clients on either instance can only find the last messages through anti-entropy.
 */
@TestPropertySource(properties = {"realtime.bus=redis", "management.metrics.use-global-registry=false"})
class TwoInstanceConvergenceTest extends ConvergenceTest {

    private static ConfigurableApplicationContext instanceB;
    private static int portB;
    private static TestRestTemplate restB;

    @SpyBean
    private RealtimeBus bus;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> TwoInstanceSupport.REDIS_PORT);
    }

    @BeforeAll
    static void startInstanceB() {
        TwoInstanceSupport.redis.isRunning(); // the singleton Redis container (started by its class)
        instanceB = TwoInstanceSupport.startInstance(Map.of("ws.push.threads", 1, "ws.push.queue-capacity", 2));
        portB = ((WebServerApplicationContext) instanceB).getWebServer().getPort();
        restB = TwoInstanceSupport.jdkRest(portB);
    }

    @AfterAll
    static void stopInstanceB() {
        if (instanceB != null) {
            instanceB.close();
        }
    }

    @Override
    protected int portFor(int index) {
        return index % 2 == 0 ? super.portFor(index) : portB;
    }

    @Override
    protected TestRestTemplate restFor(int index) {
        return index % 2 == 0 ? super.restFor(index) : restB;
    }

    @Override
    protected List<ConnectionRegistry> closableRegistries() {
        return List.of(super.closableRegistries().get(0), instanceB.getBean(ConnectionRegistry.class));
    }

    @Override
    protected void installExtraFaults(FaultInjector fi) {
        doAnswer(inv -> {
            if (fi.dropAllPushes.get()) {
                fi.count("tail dropped publish");
                return null;
            }
            if (fi.on.get() && fi.roll(0.20)) {
                fi.count("bus dropped publish");
                return null;
            }
            return inv.callRealMethod();
        }).when(bus).publish(any(Envelope.class));
    }
}
