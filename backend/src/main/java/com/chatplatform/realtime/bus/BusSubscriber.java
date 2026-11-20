package com.chatplatform.realtime.bus;

import com.chatplatform.realtime.websocket.ConnectionRegistry;
import com.chatplatform.realtime.websocket.RealtimeSchedulingConfig;
import com.chatplatform.shared.metrics.ChatMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Receives {@code chat:rt} envelopes (ADR-0013). An envelope from this instance is ignored: its sockets were served
 * locally before it was published, so delivering the echo would push twice. Others are handed to the same bounded
 * executors as local deliveries, never run on the listener thread:
 * pushes on the push executor (dropped and counted when full), closes on the realtime scheduler.
 */
@Component
@ConditionalOnProperty(name = "realtime.bus", havingValue = "redis")
public class BusSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(BusSubscriber.class);

    private final RealtimeBus bus;
    private final ConnectionRegistry registry;
    private final ObjectMapper mapper;
    private final ChatMetrics metrics;
    private final ThreadPoolExecutor pushExecutor;
    private final ThreadPoolTaskScheduler closer;

    public BusSubscriber(RealtimeBus bus, ConnectionRegistry registry, ObjectMapper mapper, ChatMetrics metrics,
                         @Qualifier(RealtimeSchedulingConfig.WS_PUSH_EXECUTOR) ThreadPoolExecutor pushExecutor,
                         @Qualifier(RealtimeSchedulingConfig.WS_TASK_SCHEDULER) ThreadPoolTaskScheduler closer) {
        this.bus = bus;
        this.registry = registry;
        this.mapper = mapper;
        this.metrics = metrics;
        this.pushExecutor = pushExecutor;
        this.closer = closer;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        Envelope e;
        try {
            e = mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), Envelope.class);
        } catch (Exception ex) {
            log.warn("Ignoring an unreadable chat:rt envelope: {}", ex.toString());
            return;
        }
        if (bus.instanceId().equals(e.origin())) {
            return; // our own echo: delivered locally already
        }
        metrics.rtReceived(e.kind());
        try {
            if (e.kind() == BusKind.close_user) {
                for (UUID userId : e.userIds()) {
                    closer.execute(() -> registry.closeAll(userId, new CloseStatus(e.closeCode(), e.closeReason())));
                }
            } else {
                pushExecutor.execute(() -> registry.sendToUsers(e.userIds(), new TextMessage(e.frame()),
                        e.excludeSessionId()));
            }
        } catch (RejectedExecutionException ex) {
            metrics.pushDropped(); // at most once: clients recover from history
        }
    }
}
