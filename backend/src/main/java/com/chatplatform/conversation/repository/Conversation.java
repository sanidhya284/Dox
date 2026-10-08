package com.chatplatform.conversation.repository;

import java.time.Instant;
import java.util.UUID;

public record Conversation(
        UUID id,
        String type,
        String title,
        String directKey,
        UUID createdBy,
        long lastSeq,
        Instant lastMessageAt,
        Instant createdAt
) {}
