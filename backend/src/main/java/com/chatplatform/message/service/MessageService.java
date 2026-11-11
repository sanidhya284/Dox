package com.chatplatform.message.service;

import com.chatplatform.message.MessageLimits;
import com.chatplatform.conversation.service.ConversationService;
import com.chatplatform.conversation.repository.ConversationRepository;
import com.chatplatform.message.dto.MessagePage;
import com.chatplatform.message.dto.MessageResponse;
import com.chatplatform.message.dto.SendMessageRequest;
import com.chatplatform.message.repository.Message;
import com.chatplatform.message.repository.MessageRepository;
import com.chatplatform.message.event.MessageCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import com.chatplatform.shared.metrics.ChatMetrics;
import com.chatplatform.shared.metrics.ChatMetrics.Channel;
import com.chatplatform.shared.metrics.ChatMetrics.SendOutcome;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final ApplicationEventPublisher events;
    private final ChatMetrics metrics;
    private final com.chatplatform.shared.ratelimit.RateLimits rateLimits;
    private final com.chatplatform.user.service.UserService userService;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public MessageService(MessageRepository messageRepository,
                          ConversationRepository conversationRepository,
                          ConversationService conversationService,
                          ApplicationEventPublisher events,
                          ChatMetrics metrics,
                          com.chatplatform.shared.ratelimit.RateLimits rateLimits,
                          com.chatplatform.user.service.UserService userService,
                          org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.rateLimits = rateLimits;
        this.userService = userService;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.events = events;
        this.metrics = metrics;
    }

    public MessageResponse sendMessage(UUID conversationId, UUID senderId, SendMessageRequest request) {
        return sendMessage(conversationId, senderId, request, null);
    }

    /**
     * Same as {@link #sendMessage(UUID, UUID, SendMessageRequest)}; {@code originSessionId} is the
     * WebSocket session the send came from (null for REST), carried on the push event so the origin
     * socket is not pushed its own message (it gets the ack).
     *
     * <p>Metrics (ADR-0009): {@code chat.messages.sent}, channel ws when there is an origin session,
     * else rest. Created and duplicate are counted after commit; rejected when this method refuses.
     *
     * <p>Order (ADR-0012 Decision 10, amended): outside any transaction, (a) the body length, (b) the caller is
     * not a removed account (401), (c) a stored message with this sender and clientMsgId in this conversation
     * with this body is acked at once and uses no budget, (d) the per-user send limit. Only then (e) the send
     * transaction, which repeats the idempotency check under the conversation row lock for correctness. The
     * limiter never runs while that lock is held (Phase 4 makes it a network call to Redis).
     */
    public MessageResponse sendMessage(UUID conversationId, UUID senderId, SendMessageRequest request,
                                       String originSessionId) {
        Channel channel = (originSessionId == null) ? Channel.REST : Channel.WS;
        try {
            checkBodyLength(request);
            boolean guest = userService.requireActive(senderId).guest();
            Optional<Message> stored = messageRepository.findByClientMsgId(senderId, request.clientMsgId());
            if (stored.isPresent() && stored.get().conversationId().equals(conversationId)
                    && stored.get().body().equals(request.body())) {
                metrics.messageSent(channel, SendOutcome.DUPLICATE);
                return toResponse(stored.get());
            }
            rateLimits.checkSend(senderId, guest);
            return tx.execute(status -> send(conversationId, senderId, request, originSessionId, channel));
        } catch (ApiException e) {
            metrics.messageSent(channel, SendOutcome.REJECTED);
            throw e;
        }
    }

    /**
     * A message the server sends itself (the welcome bot; ADR-0012). The same transaction, lock and seq code as
     * a user's send; it skips only the per-user limits (step 3). Counted under channel "system".
     */
    @Transactional
    public MessageResponse sendAsSystem(UUID conversationId, UUID senderId, String body) {
        return send(conversationId, senderId, new SendMessageRequest(UUID.randomUUID(), body), null, Channel.SYSTEM);
    }

    /** Account removal (ADR-0012): deletes every message of conversations that are about to be deleted. */
    @Transactional
    public void deleteAllInConversations(java.util.Collection<UUID> conversationIds) {
        messageRepository.deleteInConversations(conversationIds);
    }

    private MessageResponse send(UUID conversationId, UUID senderId, SendMessageRequest request,
                                 String originSessionId, Channel channel) {
        // 0. Body length: enforced here too, so callers without REST validation (WebSocket, 2c) share it.
        //    Pure input validation, checked before membership, so it reveals nothing about the conversation.
        checkBodyLength(request);

        // 1. requireParticipant (unchanged)
        conversationService.requireParticipant(conversationId, senderId);

        // 2. lockForUpdate
        conversationRepository.lockForUpdate(conversationId);

        // 3. findByClientMsgId check
        Optional<Message> existing = messageRepository.findByClientMsgId(senderId, request.clientMsgId());
        if (existing.isPresent()) {
            Message existingMsg = existing.get();
            if (existingMsg.conversationId().equals(conversationId) && existingMsg.body().equals(request.body())) {
                metrics.messageSent(channel, SendOutcome.DUPLICATE);
                return toResponse(existingMsg);
            } else {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
        }

        // 3b. Only a NEW message gets here: the DM recipient must be available (ADR-0012). The send limit was
        //     checked before this transaction (sendMessage), never under the row lock.
        conversationService.requireRecipientAvailable(conversationId, senderId);

        // 4. incrementAndGetLastSeq, then insert
        long seq = conversationRepository.incrementAndGetLastSeq(conversationId);

        Optional<Message> inserted = messageRepository.insert(
                conversationId, seq, senderId, request.clientMsgId(), request.body());
        
        // 5. If the insert returns empty, throw ApiException
        if (inserted.isPresent()) {
            MessageResponse created = toResponse(inserted.get());
            // Only a NEW row is announced (duplicates returned above are never pushed again).
            // Listeners deliver it after commit (realtime: MessagePushListener).
            events.publishEvent(new MessageCreatedEvent(created, originSessionId));
            metrics.messageSent(channel, SendOutcome.CREATED);
            return created;
        } else {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
    }

    private static void checkBodyLength(SendMessageRequest request) {
        if (request.body() != null && request.body().length() > MessageLimits.MAX_BODY_CHARS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Message body must be at most " + MessageLimits.MAX_BODY_CHARS + " characters");
        }
    }

    @Transactional(readOnly = true)
    public MessagePage getMessages(UUID conversationId, UUID currentUserId, Long beforeSeq, int limit) {
        return getMessages(conversationId, currentUserId, beforeSeq, null, limit);
    }

    /**
     * History paging. {@code before}: newest first (scrolling back). {@code after}: oldest first
     * (catching up, Phase 3; ADR-0008). Both use limit + 1 for an exact hasMore; nextCursor is set only
     * while hasMore is true. Membership is checked first (same 404 rule), then the parameters.
     */
    @Transactional(readOnly = true)
    public MessagePage getMessages(UUID conversationId, UUID currentUserId, Long beforeSeq, Long afterSeq, int limit) {
        // IDOR check
        conversationService.requireParticipant(conversationId, currentUserId);
        if (beforeSeq != null && afterSeq != null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Use either before or after, not both");
        }
        if (afterSeq != null) {
            if (afterSeq < 0) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "after must not be negative");
            }
            int forwardLimit = Math.max(1, Math.min(100, limit));
            List<Message> fetchedAfter = messageRepository.findMessagesAfter(conversationId, afterSeq, forwardLimit + 1);
            boolean more = fetchedAfter.size() > forwardLimit;
            List<MessageResponse> page = (more ? fetchedAfter.subList(0, forwardLimit) : fetchedAfter)
                    .stream().map(this::toResponse).toList();
            return new MessagePage(page, more ? page.getLast().seq() : null, more);
        }

        long cursor = (beforeSeq != null) ? beforeSeq : Long.MAX_VALUE;
        // Clamp limit between 1 and 100
        int actualLimit = Math.max(1, Math.min(100, limit));

        List<Message> fetched = messageRepository.findMessages(conversationId, cursor, actualLimit + 1);
        boolean hasMore = fetched.size() > actualLimit;
        List<Message> trimmed = hasMore ? fetched.subList(0, actualLimit) : fetched;

        List<MessageResponse> responses = trimmed.stream().map(this::toResponse).toList();
        Long nextCursor = hasMore ? responses.getLast().seq() : null;

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
