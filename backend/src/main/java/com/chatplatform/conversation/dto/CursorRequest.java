package com.chatplatform.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/conversations/{id}/cursor. Only presence is validated here; the kind and the seq
 * range are validated in ConversationService AFTER the membership check, so a non-member can never
 * learn anything about a conversation from a validation error.
 */
public record CursorRequest(
        @NotBlank(message = "kind is required (\"delivered\" or \"read\")")
        String kind,
        @NotNull(message = "seq is required")
        Long seq
) {}
