package com.chatplatform.conversation.service;

import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.ParticipantCursor;
import com.chatplatform.conversation.dto.ConversationHead;
import com.chatplatform.conversation.dto.CursorResult;
import com.chatplatform.conversation.dto.LastMessage;
import com.chatplatform.conversation.dto.ParticipantInfo;
import com.chatplatform.user.dto.UserSummary;
import com.chatplatform.conversation.event.CursorAdvancedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.conversation.dto.CreateGroupRequest;
import com.chatplatform.conversation.repository.Conversation;
import com.chatplatform.conversation.repository.ConversationParticipant;
import com.chatplatform.conversation.repository.ConversationRepository;
import com.chatplatform.conversation.repository.ParticipantRepository;
import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import com.chatplatform.shared.metrics.ChatMetrics;
import com.chatplatform.shared.metrics.ChatMetrics.CursorOutcome;
import com.chatplatform.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ParticipantRepository participantRepository;
    private final ApplicationEventPublisher events;
    private final UserService userService;
    private final ChatMetrics metrics;

    public ConversationService(ConversationRepository conversationRepository,
                               ParticipantRepository participantRepository,
                               UserService userService,
                               ApplicationEventPublisher events,
                               ChatMetrics metrics) {
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.userService = userService;
        this.events = events;
        this.metrics = metrics;
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

        long unreadCount = participantRepository.unreadCount(conversationId, currentUserId);
        List<ParticipantCursor> cursors = participantRepository.findActive(conversationId).stream()
                .map(p -> new ParticipantCursor(p.userId(), p.lastDeliveredSeq(), p.lastReadSeq()))
                .toList();

        List<UUID> one = List.of(conversationId);
        return new ConversationResponse(
                conv.id(), conv.type(), conv.title(), participants,
                unreadCount, conv.lastMessageAt(), conv.createdAt(),
                me.lastReadSeq(), cursors, conv.lastSeq(),
                memberInfo(one).getOrDefault(conversationId, List.of()),
                conversationRepository.lastMessages(one).get(conversationId));
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> getUserConversations(UUID userId) {
        List<Conversation> conversations = conversationRepository.findByUserId(userId);
        Map<UUID, Long> unread = participantRepository.unreadCountsForUser(userId); // one grouped query
        List<UUID> ids = conversations.stream().map(Conversation::id).toList();
        Map<UUID, List<ParticipantInfo>> members = memberInfo(ids);                // two queries for all
        Map<UUID, LastMessage> last = conversationRepository.lastMessages(ids);   // one query for all
        return conversations.stream().map(conv -> {
            long lastReadSeq = participantRepository.find(conv.id(), userId)
                    .map(ConversationParticipant::lastReadSeq)
                    .orElse(0L);
            long unreadCount = unread.getOrDefault(conv.id(), 0L);
            List<UUID> participants = participantRepository.findParticipantIdsByConversation(conv.id());
            return new ConversationResponse(
                    conv.id(), conv.type(), conv.title(), participants,
                    unreadCount, conv.lastMessageAt(), conv.createdAt(),
                    lastReadSeq, null, conv.lastSeq(),
                    members.getOrDefault(conv.id(), List.of()), last.get(conv.id()));
        }).toList();
    }

    /**
     * Every member who ever joined each conversation, with public names (ADR-0012). Names come from UserService
     * (one batched query), never from the user repository: a domain doesn't read another domain's tables.
     */
    private Map<UUID, List<ParticipantInfo>> memberInfo(List<UUID> conversationIds) {
        List<ParticipantRepository.Membership> rows = participantRepository.findAllMembers(conversationIds);
        Map<UUID, UserSummary> users = userService.summaries(
                rows.stream().map(ParticipantRepository.Membership::userId).collect(java.util.stream.Collectors.toSet()));
        Map<UUID, List<ParticipantInfo>> out = new java.util.HashMap<>();
        for (ParticipantRepository.Membership row : rows) {
            UserSummary u = users.get(row.userId());
            if (u == null) {
                continue;
            }
            out.computeIfAbsent(row.conversationId(), k -> new java.util.ArrayList<>())
                    .add(new ParticipantInfo(u.id(), u.username(), u.displayName(), u.isGuest(), u.isBot(),
                            u.isDeleted(), row.left()));
        }
        return out;
    }
    
    public void requireParticipant(UUID conversationId, UUID userId) {
        if (!participantRepository.isParticipant(conversationId, userId)) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
    }
    
    /**
     * Ids of the conversation's current participants: rows with {@code left_at IS NULL}
     * (ParticipantRepository.findParticipantIdsByConversation). Used by realtime to address pushes
     * without reaching into this module's repositories.
     */
    public List<UUID> activeParticipantIds(UUID conversationId) {
        return participantRepository.findParticipantIdsByConversation(conversationId);
    }

    /**
     * Moves the user's delivered or read cursor forward (ADR-0007). Order of checks:
     * 1. membership (non-member, left participant and unknown conversation give the same 404),
     * 2. kind and seq (negative, or beyond the conversation's last_seq: VALIDATION_ERROR),
     * 3. the guarded update (a stale cursor writes nothing).
     */
    @Transactional
    public CursorResult advanceCursor(UUID conversationId, UUID userId, String kind, long seq) {
        return advanceCursor(conversationId, userId, kind, seq, null);
    }

    /**
     * Same as {@link #advanceCursor(UUID, UUID, String, long)}; {@code originSessionId} is the WebSocket
     * session the update came from (null for REST). If a cursor advanced, publishes
     * {@link CursorAdvancedEvent} (delivered after commit by realtime).
     */
    @Transactional
    public CursorResult advanceCursor(UUID conversationId, UUID userId, String kind, long seq, String originSessionId) {
        requireParticipant(conversationId, userId);
        boolean read = "read".equals(kind);
        if (!read && !"delivered".equals(kind)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "kind must be \"delivered\" or \"read\"");
        }
        if (seq < 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "seq must not be negative");
        }
        long lastSeq = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND))
                .lastSeq();
        if (seq > lastSeq) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "seq is beyond the conversation's latest message");
        }
        int updated = read
                ? participantRepository.advanceRead(conversationId, userId, seq)
                : participantRepository.advanceDelivered(conversationId, userId, seq);
        ConversationParticipant me = participantRepository.find(conversationId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND));
        boolean advanced = updated == 1;
        metrics.cursorUpdate(advanced ? CursorOutcome.ADVANCED : CursorOutcome.STALE);
        if (advanced) {
            events.publishEvent(new CursorAdvancedEvent(conversationId, userId,
                    me.lastDeliveredSeq(), me.lastReadSeq(), originSessionId));
        }
        return new CursorResult(advanced, me.lastDeliveredSeq(), me.lastReadSeq());
    }

    /** [conversationId, lastSeq] for every conversation the user is active in (anti-entropy, ADR-0008). */
    @Transactional(readOnly = true)
    public List<ConversationHead> heads(UUID userId) {
        metrics.headsRequest();
        return conversationRepository.findHeadsForUser(userId);
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
