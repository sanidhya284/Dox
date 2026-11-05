package com.chatplatform.conversation.dto;

import java.util.UUID;

/** One active participant's delivered and read cursors (ADR-0007). */
public record ParticipantCursor(UUID userId, long lastDeliveredSeq, long lastReadSeq) {
}
