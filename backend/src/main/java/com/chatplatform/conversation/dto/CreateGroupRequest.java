package com.chatplatform.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record CreateGroupRequest(
        @NotBlank(message = "Title is required")
        String title,

        @NotEmpty(message = "Group must have at least one other member")
        List<UUID> memberIds
) {}
