package com.chatplatform.message;

/** Limits on message content, shared by the REST and (Phase 2c) WebSocket send paths. */
public final class MessageLimits {

    /**
     * Maximum message body length in UTF-16 chars ({@code String.length()}): characters outside the
     * Basic Multilingual Plane, such as most emoji, count as 2. Enough for chat text and a pasted
     * snippet; keeps rows and WebSocket frames bounded.
     */
    public static final int MAX_BODY_CHARS = 4000;

    private MessageLimits() {
    }
}
