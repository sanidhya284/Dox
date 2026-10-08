package com.chatplatform.conversation.repository;

import java.time.Instant;
import java.util.UUID;

public record ConversationParticipant(
        UUID conversationId,
        UUID userId,
        String role,
        Instant joinedAt,
        Instant leftAt,
        long lastDeliveredSeq,
        long lastReadSeq
) {}
