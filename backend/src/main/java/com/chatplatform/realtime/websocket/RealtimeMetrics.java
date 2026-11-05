package com.chatplatform.realtime.websocket;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Gauges read from realtime state (ADR-0009): {@code chat.ws.sessions} (registered sockets) and the
 * push executor's {@code executor.*} meters (queued, active, completed, rejected...) tagged
 * {@code name=wsPushExecutor}. A binder keeps Micrometer out of ConnectionRegistry itself.
 */
@Component
public class RealtimeMetrics implements MeterBinder {

    private final ConnectionRegistry registry;
    private final ThreadPoolExecutor pushExecutor;

    public RealtimeMetrics(ConnectionRegistry registry,
                           @Qualifier(RealtimeSchedulingConfig.WS_PUSH_EXECUTOR) ThreadPoolExecutor pushExecutor) {
        this.registry = registry;
        this.pushExecutor = pushExecutor;
    }

    @Override
    public void bindTo(MeterRegistry meters) {
        Gauge.builder("chat.ws.sessions", registry, ConnectionRegistry::sessionCount)
                .description("Registered WebSocket sessions").register(meters);
        new ExecutorServiceMetrics(pushExecutor, RealtimeSchedulingConfig.WS_PUSH_EXECUTOR, Tags.empty())
                .bindTo(meters);
    }
}
