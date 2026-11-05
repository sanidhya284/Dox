package com.chatplatform.conversation.event;

import java.util.UUID;

/**
 * Published by ConversationService inside the cursor transaction, only when a participant's delivered
 * or read cursor actually moved forward (ADR-0007). Listeners that deliver it should use
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}. {@code originSessionId} is the WebSocket
 * session the update came from (null for REST), so that socket is not pushed its own cursor.
 */
public record CursorAdvancedEvent(UUID conversationId, UUID userId, long lastDeliveredSeq, long lastReadSeq,
                                  String originSessionId) {
}
