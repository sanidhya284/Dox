package com.chatplatform.conversation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A conversation as seen by one user. {@code unreadCount} counts messages after the user's read
 * cursor that other participants sent (your own messages are never unread). {@code participantCursors}
 * is filled by GET /api/conversations/{id} only (null in the list).
 */
public record ConversationResponse(
        UUID id,
        String type,
        String title,
        List<UUID> participantIds,
        long unreadCount,
        Instant lastMessageAt,
        Instant createdAt,
        long myLastReadSeq,
        List<ParticipantCursor> participantCursors
) {}
