package com.chatplatform.conversation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateDirectRequest(
        @NotNull(message = "Other user ID is required")
        UUID otherUserId
) {}
