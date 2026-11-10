package com.chatplatform.shared.event;

import java.util.UUID;

/**
 * Every session of this user has ended (ADR-0012): the account was removed. Published inside the removing
 * transaction; realtime closes the user's sockets after commit through its single "close user" path, which
 * Phase 4 routes to every instance. The publisher never knows who listens.
 */
public record UserSessionsEndedEvent(UUID userId, Reason reason) {

    public enum Reason {
        /** A "Try the demo" guest reached its lifetime: close code 4008. */
        GUEST_EXPIRED,
        /** An operator removed the account (runbook): close code 4002, like revoked credentials. */
        ACCOUNT_REMOVED
    }
}
