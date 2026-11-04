package com.chatplatform.message.event;

import com.chatplatform.message.dto.MessageResponse;

/**
 * Published by MessageService inside the send transaction, only when a NEW message row was inserted
 * (never for an idempotent duplicate). Listeners that deliver it should use
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so nothing is delivered for a send that
 * rolls back. {@code originSessionId} is the WebSocket session the send came from (null for REST), so
 * a push can skip the socket that already gets the ack. The message module does not know who listens.
 */
public record MessageCreatedEvent(MessageResponse message, String originSessionId) {
}
