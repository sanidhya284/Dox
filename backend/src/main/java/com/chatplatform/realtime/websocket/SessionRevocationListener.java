package com.chatplatform.realtime.websocket;

import com.chatplatform.auth.event.RefreshTokenFamilyRevokedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Decision 5.1 B: when auth revokes a refresh-token family (replay / theft), close all of that user's
 * sockets with 4002.
 *
 * <p>AFTER_COMMIT: the revocation commits although the request answers 401
 * ({@code noRollbackFor = ApiException.class}); closing only after commit means sockets are never
 * closed for a revocation that did not persist. The close itself runs on the realtime scheduler, not
 * on the request thread: Tomcat writes the close frame synchronously (bounded, by default 50 ms per
 * session for non-1000 codes), and the public /api/auth/refresh must not wait on peers.
 * See ADR-0005.
 */
@Component
public class SessionRevocationListener {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationListener.class);

    private final ConnectionRegistry registry;
    private final ThreadPoolTaskScheduler executor;

    public SessionRevocationListener(
            ConnectionRegistry registry,
            @Qualifier(RealtimeSchedulingConfig.WS_TASK_SCHEDULER) ThreadPoolTaskScheduler executor) {
        this.registry = registry;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFamilyRevoked(RefreshTokenFamilyRevokedEvent event) {
        try {
            executor.execute(() -> registry.closeAll(event.userId(), WsCloseStatuses.CREDENTIALS_REVOKED));
        } catch (RuntimeException e) {
            // Never fail the (already committed) request because sockets could not be scheduled to close.
            log.error("Could not schedule closing sockets of user {} after family revocation", event.userId(), e);
        }
    }
}
