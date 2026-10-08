package com.chatplatform.conversation.service;

import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.conversation.dto.CreateGroupRequest;
import com.chatplatform.conversation.repository.Conversation;
import com.chatplatform.conversation.repository.ConversationParticipant;
import com.chatplatform.conversation.repository.ConversationRepository;
import com.chatplatform.conversation.repository.ParticipantRepository;
import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import com.chatplatform.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ParticipantRepository participantRepository;
    private final UserService userService;

    public ConversationService(ConversationRepository conversationRepository,
                               ParticipantRepository participantRepository,
                               UserService userService) {
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.userService = userService;
    }

    @Transactional
    public ConversationResponse createDirect(UUID currentUserId, CreateDirectRequest request) {
        if (currentUserId.equals(request.otherUserId())) {
            throw new ApiException(ErrorCode.CANNOT_DM_SELF);
        }

        // Verify user exists (throws if not found)
        userService.getUserById(request.otherUserId());

        // Canonical direct_key
        String directKey = generateDirectKey(currentUserId, request.otherUserId());

        Conversation conversation = conversationRepository.insertDirect(currentUserId, directKey);

        participantRepository.insert(conversation.id(), currentUserId, "MEMBER");
        participantRepository.insert(conversation.id(), request.otherUserId(), "MEMBER");

        return buildResponse(conversation.id(), currentUserId);
    }

    @Transactional
    public ConversationResponse createGroup(UUID currentUserId, CreateGroupRequest request) {
        // Verify all members exist
        for (UUID memberId : request.memberIds()) {
            userService.getUserById(memberId);
        }

        Conversation conversation = conversationRepository.insertGroup(currentUserId, request.title());

        participantRepository.insert(conversation.id(), currentUserId, "ADMIN");
        participantRepository.insertMultiple(conversation.id(), request.memberIds(), "MEMBER");

        return buildResponse(conversation.id(), currentUserId);
    }

    @Transactional(readOnly = true)
    public ConversationResponse getConversation(UUID conversationId, UUID currentUserId) {
        // IDOR protection: user must be a participant. If not found, do not reveal if it exists.
        if (!participantRepository.isParticipant(conversationId, currentUserId)) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return buildResponse(conversationId, currentUserId);
    }

    // Private helper — no @Transactional annotation, runs in the caller's existing transaction.
    private ConversationResponse buildResponse(UUID conversationId, UUID currentUserId) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND));

        List<UUID> participants = participantRepository.findParticipantIdsByConversation(conversationId);

        ConversationParticipant me = participantRepository.find(conversationId, currentUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND));

        long unreadCount = Math.max(0, conv.lastSeq() - me.lastReadSeq());

        return new ConversationResponse(
                conv.id(), conv.type(), conv.title(), participants,
                unreadCount, conv.lastMessageAt(), conv.createdAt());
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> getUserConversations(UUID userId) {
        List<Conversation> conversations = conversationRepository.findByUserId(userId);
        return conversations.stream().map(conv -> {
            long lastReadSeq = participantRepository.find(conv.id(), userId)
                    .map(ConversationParticipant::lastReadSeq)
                    .orElse(0L);
            long unreadCount = Math.max(0, conv.lastSeq() - lastReadSeq);
            List<UUID> participants = participantRepository.findParticipantIdsByConversation(conv.id());
            return new ConversationResponse(
                    conv.id(), conv.type(), conv.title(), participants,
                    unreadCount, conv.lastMessageAt(), conv.createdAt());
        }).toList();
    }
    
    public void requireParticipant(UUID conversationId, UUID userId) {
        if (!participantRepository.isParticipant(conversationId, userId)) {
            throw new ApiException(ErrorCode.NOT_A_PARTICIPANT);
        }
    }
    
    public void requireConversationExists(UUID conversationId) {
        if (conversationRepository.findById(conversationId).isEmpty()) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
    }

    private String generateDirectKey(UUID id1, UUID id2) {
        if (id1.compareTo(id2) < 0) {
            return id1 + ":" + id2;
        } else {
            return id2 + ":" + id1;
        }
    }
}
