package com.chatplatform.realtime.bus;

import com.chatplatform.shared.metrics.ChatMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import com.chatplatform.shared.redis.RedisGuard;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * realtime.bus=redis (ADR-0013): publishes envelopes on the Redis channel {@code chat:rt}.
 *
 * <p><b>A publish never blocks a push-executor thread.</b> {@link #publish} only enqueues on this bus's own
 * single-thread executor with a bounded queue ({@code realtime.bus.publish-queue}); a full queue drops the envelope
 * and counts it. The Redis call goes through the {@link RedisGuard} circuit breaker, so during an outage publishes
 * are dropped at once instead of each waiting for the command timeout. A failed publish is caught and counted, never
 * propagated: the message was already stored and acked.
 *
 * <p><b>Its own connection.</b> The bus publishes on a Lettuce connection it creates and owns, not on the shared one
 * that the limiter and socket cap use, so a stuck publish can't delay a limiter call and the other way round
 * (Lettuce pipelines every command of one connection in order).
 */
@Component
@ConditionalOnProperty(name = "realtime.bus", havingValue = "redis")
public class RedisBus implements RealtimeBus, DisposableBean {

    public static final String CHANNEL = "chat:rt";
    private static final Logger log = LoggerFactory.getLogger(RedisBus.class);

    private final String instanceId = UUID.randomUUID().toString();
    private final LettuceConnectionFactory connections;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ChatMetrics metrics;
    private final RedisGuard guard;
    private final ThreadPoolExecutor publisher;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();

    public RedisBus(RedisProperties properties, ObjectMapper mapper, ChatMetrics metrics, RedisGuard guard,
                    @Value("${realtime.bus.publish-queue:10000}") int queueCapacity) {
        this.connections = RedisBusConfig.dedicatedFactory(properties);
        this.redis = new StringRedisTemplate(connections);
        this.mapper = mapper;
        this.metrics = metrics;
        this.guard = guard;
        this.publisher = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), r -> {
                    Thread t = new Thread(r, "rt-bus-publish");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void publish(Envelope envelope) {
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            lost.incrementAndGet();
            metrics.rtPublishFailed(envelope.kind());
            log.error("Could not serialise a {} envelope", envelope.kind(), e);
            return;
        }
        pending.incrementAndGet();
        try {
            publisher.execute(() -> send(envelope.kind(), json));
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            lost.incrementAndGet();
            metrics.rtPublishDropped(envelope.kind()); // queue full: at most once; clients recover from history
        }
    }

    private void send(BusKind kind, String json) {
        try {
            if (guard.run(RedisGuard.Op.PUBLISH, () -> redis.convertAndSend(CHANNEL, json))) {
                metrics.rtPublished(kind);
            } else {
                lost.incrementAndGet();
                metrics.rtPublishFailed(kind); // Redis error, or breaker open: dropped without a wait
            }
        } finally {
            pending.decrementAndGet();
        }
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public boolean flush(Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (pending.get() > 0) {
            if (System.nanoTime() >= end) {
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return lost.get() == 0;
    }

    @Override
    public void destroy() {
        publisher.shutdownNow();
        connections.destroy();
    }
}
