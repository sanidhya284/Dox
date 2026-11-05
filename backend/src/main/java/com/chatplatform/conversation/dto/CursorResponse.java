package com.chatplatform.conversation.dto;

/** The caller's cursors after a cursor update. */
public record CursorResponse(long lastDeliveredSeq, long lastReadSeq) {
}
