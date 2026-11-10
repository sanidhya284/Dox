package com.chatplatform.conversation.dto;

import java.util.UUID;

/**
 * A member of a conversation as the UI renders it (ADR-0012): every user who ever joined, including those who
 * left ({@code left}) and anonymized accounts ({@code isDeleted}), so old messages still show a name. No email.
 */
public record ParticipantInfo(UUID id, String username, String displayName, boolean isGuest, boolean isBot,
                              boolean isDeleted, boolean left) {
}
