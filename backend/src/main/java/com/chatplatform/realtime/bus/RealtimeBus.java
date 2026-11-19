package com.chatplatform.realtime.bus;

import java.time.Duration;

/**
 * Cross-instance delivery (ADR-0013). Callers have already delivered to their own instance's sockets; the bus
 * carries the same delivery to the other instances. <b>At most once:</b> a publish may be lost (Redis down, a
 * subscriber reconnecting, a full publish queue); clients recover from Postgres through the Phase 3 sync.
 *
 * <p>{@link #publish} never blocks the caller and never throws: it only enqueues.
 */
public interface RealtimeBus {

    void publish(Envelope envelope);

    /** This instance's id, the {@code origin} of what it publishes. */
    String instanceId();

    /**
     * Waits at most {@code timeout} for what was published so far to leave this instance. Returns {@code true} only
     * if every publish so far reached Redis; {@code false} if any was lost (Redis down or paused, breaker open, queue
     * full) or the timeout passed. Used by the one-shot admin runner (runbook 9a). The local bus never crosses
     * instances, so it returns {@code false}.
     */
    default boolean flush(Duration timeout) {
        return false;
    }
}
