package com.chatplatform.realtime.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Starts the {@code chat:rt} subscription without making startup depend on Redis (ADR-0013 Decision 5).
 * Spring's listener container subscribes when the context starts and fails the whole startup if Redis is
 * unreachable then. So the container doesn't auto-start; this starts it on a background thread and retries every
 * {@code retryMillis} until the subscription is up. After that, the container's own recovery handles connection
 * losses. Until it is up, this instance receives nothing from the bus (at most once; clients recover from history).
 */
class BusSubscription implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BusSubscription.class);

    private final RedisMessageListenerContainer container;
    private final long retryMillis;
    private volatile Thread starter;
    private volatile boolean running;

    BusSubscription(RedisMessageListenerContainer container, long retryMillis) {
        this.container = container;
        this.retryMillis = retryMillis;
    }

    @Override
    public void start() {
        running = true;
        starter = Thread.ofPlatform().daemon().name("rt-bus-subscribe").start(this::subscribeUntilUp);
    }

    private void subscribeUntilUp() {
        boolean warned = false;
        while (running) {
            try {
                container.start();
                if (container.isRunning()) {
                    log.info("Subscribed to {}", RedisBus.CHANNEL);
                    return;
                }
            } catch (RuntimeException e) {
                if (!warned) {
                    log.warn("Redis unreachable, {} subscription will retry every {} ms: {}", RedisBus.CHANNEL,
                            retryMillis, e.toString());
                    warned = true;
                }
                safeStop();
            }
            try {
                Thread.sleep(retryMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void safeStop() {
        try {
            container.stop();
        } catch (RuntimeException ignored) {
            // nothing to stop
        }
    }

    @Override
    public void stop() {
        running = false;
        Thread t = starter;
        if (t != null) {
            t.interrupt();
        }
        safeStop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
