package com.chatplatform.conversation.dto;

import java.time.Instant;
import java.util.UUID;

/** The newest message of a conversation, for the list preview (ADR-0012). {@code preview} is at most 120 code points. */
public record LastMessage(UUID senderId, String preview, Instant createdAt, long seq) {

    public static final int PREVIEW_CODE_POINTS = 120;

    /** Truncates by Unicode code points (never splits a surrogate pair) and marks truncation with an ellipsis. */
    public static String preview(String body) {
        if (body.codePointCount(0, body.length()) <= PREVIEW_CODE_POINTS) {
            return body;
        }
        return body.substring(0, body.offsetByCodePoints(0, PREVIEW_CODE_POINTS)) + "…";
    }
}
