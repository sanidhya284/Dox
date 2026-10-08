package com.chatplatform.message.repository;

import java.time.Instant;
import java.util.UUID;

public record Message(
        UUID id,
        UUID conversationId,
        long seq,
        UUID senderId,
        UUID clientMsgId,
        String body,
        Instant createdAt
) {}
