package com.chatplatform.message.dto;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        UUID conversationId,
        long seq,
        UUID senderId,
        UUID clientMsgId,
        String body,
        Instant createdAt
) {}

