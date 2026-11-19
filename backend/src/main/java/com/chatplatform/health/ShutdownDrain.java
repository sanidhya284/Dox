package com.chatplatform.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The first step of shutdown (ADR-0013 Decision 8). On SIGTERM this stops BEFORE the web server's graceful shutdown
 * (a higher lifecycle phase stops first). By then readiness is {@code REFUSING_TRAFFIC} (Spring Boot sets it when the
 * context starts closing), so {@link ReadinessController} answers 503 and Caddy takes the instance out of rotation;
 * this keeps the server serving normally for {@code app.shutdown.drain}. The public {@code /actuator/health} stays 200.
 * The load balancer's health check sees the 503 and stops sending new requests here; only then does the graceful
 * shutdown begin (in-flight requests finish, sockets close with 1001 and reconnect to the other instance).
 *
 * <p>Default 0: one instance has nowhere to drain to, so shutdown is as before.
 */
@Component
public class ShutdownDrain implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ShutdownDrain.class);

    private final Duration drain;
    private volatile boolean running;

    public ShutdownDrain(@Value("${app.shutdown.drain:PT0S}") Duration drain) {
        if (drain.isNegative()) {
            throw new IllegalStateException("app.shutdown.drain must not be negative");
        }
        this.drain = drain;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (drain.isZero()) {
            return;
        }
        // Spring Boot has already switched readiness to REFUSING_TRAFFIC: it does so when the context starts
        // closing, before any lifecycle bean stops (an explicit publish here was an equivalent mutant).
        log.info("Draining: readiness is REFUSING_TRAFFIC; still serving for {} before the graceful shutdown", drain);
        try {
            Thread.sleep(drain.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Drain finished; graceful shutdown starts");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops first: higher than the web server's graceful-shutdown phase. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
