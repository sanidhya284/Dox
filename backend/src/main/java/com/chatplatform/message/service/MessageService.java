package com.chatplatform.message.service;

import com.chatplatform.conversation.service.ConversationService;
import com.chatplatform.conversation.repository.ConversationRepository;
import com.chatplatform.message.dto.MessagePage;
import com.chatplatform.message.dto.MessageResponse;
import com.chatplatform.message.dto.SendMessageRequest;
import com.chatplatform.message.repository.Message;
import com.chatplatform.message.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;

    public MessageService(MessageRepository messageRepository,
                          ConversationRepository conversationRepository,
                          ConversationService conversationService) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
    }

    @Transactional
    public MessageResponse sendMessage(UUID conversationId, UUID senderId, SendMessageRequest request) {
        // 1. requireParticipant (unchanged)
        conversationService.requireParticipant(conversationId, senderId);

        // 2. lockForUpdate
        conversationRepository.lockForUpdate(conversationId);

        // 3. findByClientMsgId check
        Optional<Message> existing = messageRepository.findByClientMsgId(senderId, request.clientMsgId());
        if (existing.isPresent()) {
            Message existingMsg = existing.get();
            if (existingMsg.conversationId().equals(conversationId) && existingMsg.body().equals(request.body())) {
                return toResponse(existingMsg);
            } else {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
        }

        // 4. incrementAndGetLastSeq, then insert
        long seq = conversationRepository.incrementAndGetLastSeq(conversationId);

        Optional<Message> inserted = messageRepository.insert(
                conversationId, seq, senderId, request.clientMsgId(), request.body());
        
        // 5. If the insert returns empty, throw ApiException
        if (inserted.isPresent()) {
            return toResponse(inserted.get());
        } else {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
    }

    @Transactional(readOnly = true)
    public MessagePage getMessages(UUID conversationId, UUID currentUserId, Long beforeSeq, int limit) {
        // IDOR check
        conversationService.requireParticipant(conversationId, currentUserId);

        long cursor = (beforeSeq != null) ? beforeSeq : Long.MAX_VALUE;
        // Clamp limit between 1 and 100
        int actualLimit = Math.max(1, Math.min(100, limit));

        List<Message> messages = messageRepository.findMessages(conversationId, cursor, actualLimit);
        
        List<MessageResponse> responses = messages.stream().map(this::toResponse).toList();
        
        Long nextCursor = null;
        boolean hasMore = false;
        
        if (responses.size() == actualLimit && !responses.isEmpty()) {
            nextCursor = responses.getLast().seq();
            hasMore = true;
        }
        
        return new MessagePage(responses, nextCursor, hasMore);
    }

    private MessageResponse toResponse(Message message) {
        return new MessageResponse(
                message.id(),
                message.conversationId(),
                message.seq(),
                message.senderId(),
                message.clientMsgId(),
                message.body(),
                message.createdAt()
        );
    }
}
