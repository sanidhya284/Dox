package com.chatplatform.message.dto;

import com.chatplatform.message.MessageLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SendMessageRequest(
        @NotNull(message = "Client message ID is required")
        UUID clientMsgId,

        @NotBlank(message = "Body cannot be blank")
        @Size(max = MessageLimits.MAX_BODY_CHARS, message = "Body must be at most " + MessageLimits.MAX_BODY_CHARS + " characters")
        String body
) {}
