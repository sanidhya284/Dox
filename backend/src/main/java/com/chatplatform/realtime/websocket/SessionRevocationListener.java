package com.chatplatform.realtime.websocket;

import com.chatplatform.auth.event.RefreshTokenFamilyRevokedEvent;
import com.chatplatform.shared.event.UserSessionsEndedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;

import java.util.UUID;

/**
 * Closes all of a user's sockets when their sessions end: a revoked refresh-token family (4002, decision
 * 5.1 B) or a removed account (4008 guest expired, 4002 removed by an operator; ADR-0012). Both go through
 * {@link #closeUser}, the one "close user" path (Phase 4 routes it to every instance).
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
        closeUser(event.userId(), WsCloseStatuses.CREDENTIALS_REVOKED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionsEnded(UserSessionsEndedEvent event) {
        closeUser(event.userId(), event.reason() == UserSessionsEndedEvent.Reason.GUEST_EXPIRED
                ? WsCloseStatuses.GUEST_EXPIRED : WsCloseStatuses.CREDENTIALS_REVOKED);
    }

    /** The single "close user" path: every socket of the user, off the calling thread. */
    void closeUser(UUID userId, CloseStatus status) {
        try {
            executor.execute(() -> registry.closeAll(userId, status));
        } catch (RuntimeException e) {
            // Never fail the (already committed) request because sockets could not be scheduled to close.
            log.error("Could not schedule closing sockets of user {} ({})", userId, status, e);
        }
    }
}
