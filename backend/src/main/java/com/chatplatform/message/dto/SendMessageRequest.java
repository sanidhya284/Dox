package com.chatplatform.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SendMessageRequest(
        @NotNull(message = "Client message ID is required")
        UUID clientMsgId,

        @NotBlank(message = "Body cannot be blank")
        String body
) {}
