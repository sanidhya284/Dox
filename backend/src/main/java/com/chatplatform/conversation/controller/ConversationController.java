package com.chatplatform.conversation.controller;

import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.conversation.dto.CreateGroupRequest;
import com.chatplatform.conversation.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/direct")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse createDirect(
            @AuthenticationPrincipal UUID currentUserId,
            @Valid @RequestBody CreateDirectRequest request) {
        return conversationService.createDirect(currentUserId, request);
    }

    @PostMapping("/group")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse createGroup(
            @AuthenticationPrincipal UUID currentUserId,
            @Valid @RequestBody CreateGroupRequest request) {
        return conversationService.createGroup(currentUserId, request);
    }

    @GetMapping
    public List<ConversationResponse> getUserConversations(
            @AuthenticationPrincipal UUID currentUserId) {
        return conversationService.getUserConversations(currentUserId);
    }

    @GetMapping("/{id}")
    public ConversationResponse getConversation(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID id) {
        return conversationService.getConversation(id, currentUserId);
    }
}
