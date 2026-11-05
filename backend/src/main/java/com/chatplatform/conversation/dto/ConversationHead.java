package com.chatplatform.conversation.dto;

import java.util.UUID;

/** A conversation's latest seq, for cheap "am I behind?" checks (Phase 3 anti-entropy, ADR-0008). */
public record ConversationHead(UUID conversationId, long lastSeq) {
}
