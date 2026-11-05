package com.chatplatform.realtime.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small scheduler owned by the realtime module: it runs the socket-lifetime closes and
 * (Phase 2b-ii step c) revocation closes, so neither runs on a request thread.
 */
@Configuration
public class RealtimeSchedulingConfig {

    public static final String WS_TASK_SCHEDULER = "wsTaskScheduler";

    public static final String WS_PUSH_EXECUTOR = "wsPushExecutor";

    /**
     * Runs message pushes (Phase 2c) off the request and WebSocket threads. Bounded on purpose: when
     * the queue is full the push is rejected (AbortPolicy), and MessagePushListener drops, logs and
     * counts it; clients recover missed messages from history. A slow push therefore never blocks a
     * sender and memory cannot grow without limit.
     */
    @Bean(name = WS_PUSH_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolExecutor wsPushExecutor(
            @Value("${ws.push.threads:2}") int threads,
            @Value("${ws.push.queue-capacity:10000}") int queueCapacity) {
        if (threads <= 0 || queueCapacity <= 0) {
            throw new IllegalStateException("ws.push.threads and ws.push.queue-capacity must be positive");
        }
        AtomicInteger n = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "ws-push-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = WS_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler wsTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ws-sched-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true); // cancelled lifetime closes don't linger in the queue
        return scheduler;
    }
}
