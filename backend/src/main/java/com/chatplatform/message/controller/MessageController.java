package com.chatplatform.message.controller;

import com.chatplatform.message.dto.MessagePage;
import com.chatplatform.message.dto.MessageResponse;
import com.chatplatform.message.dto.SendMessageRequest;
import com.chatplatform.message.service.MessageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/conversations/{conversationId}/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED) // Technically returns 201 even on idempotent retry for simplicity in Spring
    public MessageResponse sendMessage(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal UUID currentUserId,
            @Valid @RequestBody SendMessageRequest request) {
        return messageService.sendMessage(conversationId, currentUserId, request);
    }

    @GetMapping
    public MessagePage getMessages(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal UUID currentUserId,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "50") int limit) {
        return messageService.getMessages(conversationId, currentUserId, before, limit);
    }
}
