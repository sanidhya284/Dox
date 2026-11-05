package com.chatplatform.realtime.websocket;

import com.chatplatform.conversation.event.CursorAdvancedEvent;
import com.chatplatform.conversation.service.ConversationService;
import com.chatplatform.message.dto.MessageResponse;
import com.chatplatform.message.event.MessageCreatedEvent;
import com.chatplatform.realtime.websocket.protocol.FrameCodec;
import com.chatplatform.shared.metrics.ChatMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.TextMessage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pushes each new message (ADR-0006, Decision 5) after its transaction commits, to every open socket
 * of every active participant except the socket it came from (that one gets the ack).
 *
 * <p>AFTER_COMMIT: nothing is pushed for a send that rolls back. Note that a
 * {@code @TransactionalEventListener} ignores events published with no active transaction;
 * MessageService.sendMessage is always transactional.
 *
 * <p>The push runs on the bounded {@code wsPushExecutor}, never on the request or WebSocket thread.
 * When its queue is full the push is dropped, logged and counted; pushes are only an optimisation,
 * and clients recover missed messages from history.
 */
@Component
public class MessagePushListener {

    private static final Logger log = LoggerFactory.getLogger(MessagePushListener.class);

    private final ConnectionRegistry registry;
    private final ConversationService conversationService;
    private final FrameCodec codec;
    private final ThreadPoolExecutor pushExecutor;
    private final AtomicLong droppedPushes = new AtomicLong();
    private final ChatMetrics metrics;

    public MessagePushListener(ConnectionRegistry registry, ConversationService conversationService, FrameCodec codec,
                               @Qualifier(RealtimeSchedulingConfig.WS_PUSH_EXECUTOR) ThreadPoolExecutor pushExecutor,
                               ChatMetrics metrics) {
        this.metrics = metrics;
        this.registry = registry;
        this.conversationService = conversationService;
        this.codec = codec;
        this.pushExecutor = pushExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCreated(MessageCreatedEvent event) {
        MessageResponse message = event.message();
        try {
            pushExecutor.execute(() -> push(message, event.originSessionId()));
        } catch (RejectedExecutionException e) {
            long total = droppedPushes.incrementAndGet();
            metrics.pushDropped();
            log.warn("Push queue full: dropped push of message {} (conversation {}); {} dropped so far. "
                    + "Clients recover from history.", message.id(), message.conversationId(), total);
        }
    }

    /**
     * A participant's cursor advanced (ADR-0007): push a {@code cursor} frame after commit to every
     * open socket of every active participant except the origin socket (the user's other tabs get it,
     * so their unread badges clear). Same bounded executor and drop policy as message pushes.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCursorAdvanced(CursorAdvancedEvent event) {
        try {
            pushExecutor.execute(() -> pushCursor(event));
        } catch (RejectedExecutionException e) {
            long total = droppedPushes.incrementAndGet();
            metrics.pushDropped();
            log.warn("Push queue full: dropped cursor push for user {} in conversation {}; {} dropped so far.",
                    event.userId(), event.conversationId(), total);
        }
    }

    private void pushCursor(CursorAdvancedEvent event) {
        try {
            List<UUID> recipients = conversationService.activeParticipantIds(event.conversationId());
            String frame = codec.cursor(event.conversationId(), event.userId(), event.lastDeliveredSeq(), event.lastReadSeq());
            registry.sendToUsers(recipients, new TextMessage(frame), event.originSessionId());
        } catch (RuntimeException e) {
            log.error("Cursor push for user {} failed", event.userId(), e);
        }
    }

    private void push(MessageResponse message, String originSessionId) {
        try {
            List<UUID> recipients = conversationService.activeParticipantIds(message.conversationId());
            registry.sendToUsers(recipients, new TextMessage(codec.message(message)), originSessionId);
        } catch (RuntimeException e) {
            log.error("Push of message {} failed", message.id(), e);
        }
    }

    /** Pushes dropped because the push queue was full, since startup. */
    public long droppedPushes() {
        return droppedPushes.get();
    }
}
