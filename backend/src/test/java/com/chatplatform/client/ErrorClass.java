package com.chatplatform.client;

import java.util.Set;

/**
 * How the reference client treats an error frame for a send (ADR-0008). Terminal errors mean resending
 * the same request can never succeed, so the outbox entry is dropped and reported. Everything else,
 * including INTERNAL_ERROR (e.g. a failed commit) and unknown future codes, is retried with the same
 * clientMsgId. An ack timeout is also retryable.
 */
public enum ErrorClass {
    RETRYABLE, TERMINAL;

    private static final Set<String> TERMINAL_CODES =
            Set.of("VALIDATION_ERROR", "CONVERSATION_NOT_FOUND", "IDEMPOTENCY_KEY_CONFLICT");

    public static ErrorClass of(String code) {
        return TERMINAL_CODES.contains(code) ? TERMINAL : RETRYABLE;
    }
}
