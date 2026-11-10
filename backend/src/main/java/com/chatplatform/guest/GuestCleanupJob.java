package com.chatplatform.guest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Runs {@link AccountRemovalService#removeExpiredGuests} every {@code guest.cleanup-interval} (default 15 min) on
 * its own thread, so database work never delays the realtime scheduler. The first run is one interval after
 * startup. Safe on several instances at once (SKIP LOCKED).
 */
@Component
public class GuestCleanupJob implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GuestCleanupJob.class);

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    public GuestCleanupJob(AccountRemovalService removal,
                           @Value("${guest.cleanup-interval:PT15M}") Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalStateException("guest.cleanup-interval must be positive");
        }
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("guest-cleanup-");
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                removal.removeExpiredGuests();
            } catch (RuntimeException e) {
                log.error("Guest cleanup failed; will retry next interval", e);
            }
        }, Instant.now().plus(interval), interval);
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }
}
