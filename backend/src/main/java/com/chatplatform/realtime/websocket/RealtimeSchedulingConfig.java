package com.chatplatform.realtime.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * A small scheduler owned by the realtime module: it runs the socket-lifetime closes and
 * (Phase 2b-ii step c) revocation closes, so neither runs on a request thread.
 */
@Configuration
public class RealtimeSchedulingConfig {

    public static final String WS_TASK_SCHEDULER = "wsTaskScheduler";

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
