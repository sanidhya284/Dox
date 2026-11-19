package com.chatplatform.realtime.bus;

import com.chatplatform.shared.metrics.ChatMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * realtime.bus=redis (ADR-0013): publishes envelopes on the Redis channel {@code chat:rt}.
 *
 * <p><b>A publish never blocks a push-executor thread.</b> {@link #publish} only enqueues on this bus's own
 * single-thread executor with a bounded queue ({@code realtime.bus.publish-queue}); a full queue drops the envelope
 * and counts it. The Redis call (200 ms command timeout; Lettuce rejects commands while disconnected) happens on
 * that thread. A failed publish is caught and counted, never propagated: the message was already stored and acked.
 */
@Component
@ConditionalOnProperty(name = "realtime.bus", havingValue = "redis")
public class RedisBus implements RealtimeBus, DisposableBean {

    public static final String CHANNEL = "chat:rt";
    private static final Logger log = LoggerFactory.getLogger(RedisBus.class);

    private final String instanceId = UUID.randomUUID().toString();
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ChatMetrics metrics;
    private final ThreadPoolExecutor publisher;

    public RedisBus(StringRedisTemplate redis, ObjectMapper mapper, ChatMetrics metrics,
                    @Value("${realtime.bus.publish-queue:10000}") int queueCapacity) {
        this.redis = redis;
        this.mapper = mapper;
        this.metrics = metrics;
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
            metrics.rtPublishFailed(envelope.kind());
            log.error("Could not serialise a {} envelope", envelope.kind(), e);
            return;
        }
        try {
            publisher.execute(() -> send(envelope.kind(), json));
        } catch (RejectedExecutionException e) {
            metrics.rtPublishDropped(envelope.kind()); // queue full: at most once; clients recover from history
        }
    }

    private void send(BusKind kind, String json) {
        try {
            redis.convertAndSend(CHANNEL, json);
            metrics.rtPublished(kind);
        } catch (RuntimeException e) {
            metrics.rtPublishFailed(kind);
            log.debug("Publish of a {} envelope failed: {}", kind, e.toString());
        }
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public void flush(Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while ((publisher.getQueue().size() > 0 || publisher.getActiveCount() > 0) && System.nanoTime() < end) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void destroy() {
        publisher.shutdown();
    }
}
