package com.chatplatform.conversation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        String type,
        String title,
        List<UUID> participantIds,
        long unreadCount,
        Instant lastMessageAt,
        Instant createdAt
) {}
