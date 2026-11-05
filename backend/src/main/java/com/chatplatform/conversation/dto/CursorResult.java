package com.chatplatform.conversation.dto;

/**
 * Outcome of a cursor update: whether a cursor moved forward, and the participant's cursors afterwards.
 */
public record CursorResult(boolean advanced, long lastDeliveredSeq, long lastReadSeq) {
}
