package com.chatplatform.realtime.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import com.chatplatform.conversation.service.ConversationService;
import com.chatplatform.message.dto.MessageResponse;
import com.chatplatform.message.dto.SendMessageRequest;
import com.chatplatform.message.service.MessageService;
import com.chatplatform.realtime.websocket.protocol.FrameCodec;
import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.metrics.ChatMetrics;
import io.micrometer.core.instrument.Timer;
import com.chatplatform.realtime.websocket.protocol.Frames.CursorCommand;
import com.chatplatform.realtime.websocket.protocol.Frames.Parsed;
import com.chatplatform.realtime.websocket.protocol.Frames.Rejected;
import com.chatplatform.realtime.websocket.protocol.Frames.SendCommand;
import com.chatplatform.realtime.websocket.protocol.Frames.Violation;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * The /ws endpoint handler (Phase 2b-ii). Tracks each authenticated socket in the
 * {@link ConnectionRegistry}, closes it with 4001 after {@code ws.session.max-lifetime}, and forgets
 * it on close or transport error.
 *
 * <p>Incoming frames follow protocol v1 (ADR-0006), parsed by {@link FrameCodec}.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    /** Session attribute holding the scheduled lifetime close, so a normal close can cancel it. */
    static final String ATTR_LIFETIME_CLOSE = "ws_lifetime_close";

    private final ConnectionRegistry registry;
    private final TaskScheduler scheduler;
    private final Duration maxLifetime;
    private final FrameCodec codec;
    private final MessageService messageService;
    private final HeartbeatMonitor heartbeat;
    private final ConversationService conversationService;
    private final ChatMetrics metrics;

    public ChatWebSocketHandler(
            ConnectionRegistry registry,
            @Qualifier(RealtimeSchedulingConfig.WS_TASK_SCHEDULER) TaskScheduler scheduler,
            @Value("${ws.session.max-lifetime:#{null}}") Duration maxLifetime,
            FrameCodec codec,
            MessageService messageService,
            HeartbeatMonitor heartbeat,
            ConversationService conversationService,
            ChatMetrics metrics) {
        this.metrics = metrics;
        this.registry = registry;
        this.scheduler = scheduler;
        this.codec = codec;
        this.messageService = messageService;
        this.heartbeat = heartbeat;
        this.conversationService = conversationService;
        if (maxLifetime == null || maxLifetime.isZero() || maxLifetime.isNegative()) {
            throw new IllegalStateException("ws.session.max-lifetime must be set to a positive duration "
                    + "(ISO-8601, e.g. PT15M; env WS_SESSION_MAX_LIFETIME), got: " + maxLifetime);
        }
        this.maxLifetime = maxLifetime;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!(session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID) instanceof UUID userId)) {
            // Should be impossible: WsAuthHandshakeInterceptor sets the attribute on every accepted
            // handshake. Fail closed rather than keep an unauthenticated socket.
            log.warn("WebSocket session {} has no authenticated user; closing with 1008", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        setTomcatBlockingSendTimeout(session);
        if (!registry.register(userId, session)) {
            log.info("User {} reached ws.max-sockets-per-user; closing session {} with 4007", userId, session.getId());
            session.close(WsCloseStatuses.TOO_MANY_SOCKETS);
            return;
        }
        heartbeat.track(userId, session.getId());
        ScheduledFuture<?> lifetimeClose = scheduler.schedule(
                () -> closeAtEndOfLifetime(session), Instant.now().plus(maxLifetime));
        session.getAttributes().put(ATTR_LIFETIME_CLOSE, lifetimeClose);
    }

    /**
     * Bounds the thread that actually writes to this socket: the decorator's send-time limit only
     * protects OTHER senders, while the writing thread itself would otherwise wait up to Tomcat's
     * default blocking-send timeout (20 s) on a stalled peer. Tomcat reads this per-session user
     * property (its own key, as a Long) before each blocking send.
     */
    private void setTomcatBlockingSendTimeout(WebSocketSession session) {
        if (session instanceof NativeWebSocketSession nws
                && nws.getNativeSession(jakarta.websocket.Session.class) instanceof jakarta.websocket.Session ns) {
            ns.getUserProperties().put(org.apache.tomcat.websocket.Constants.BLOCKING_SEND_TIMEOUT_PROPERTY,
                    Long.valueOf(registry.sendTimeLimitMs()));
        }
    }

    private void closeAtEndOfLifetime(WebSocketSession session) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(WsCloseStatuses.SESSION_LIFETIME_REACHED);
        } catch (Exception e) {
            log.warn("Lifetime close of WebSocket session {} failed: {}", session.getId(), e.toString());
        }
    }

    /**
     * Protocol v1 (ADR-0006). Tomcat delivers one session's frames one at a time, on one thread.
     * Violations close the socket; invalid content gets an error frame and the socket stays open.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        heartbeat.touch(session.getId());
        Parsed parsed = codec.parse(message.getPayload());
        switch (parsed) {
            case Violation v -> session.close(v.status());
            case Rejected r -> reply(session, codec.error(r));
            case SendCommand cmd -> handleSend(session, cmd);
            case CursorCommand cmd -> handleCursor(session, cmd);
        }
    }

    /**
     * Persists through the same {@code @Transactional} MessageService.sendMessage as REST. This handler
     * is not transactional, so when the call returns the transaction has committed: the ack is sent
     * only after commit. If anything fails (including the commit), the client gets an error frame
     * carrying its clientMsgId instead, and nothing is persisted.
     */
    private void handleSend(WebSocketSession session, SendCommand cmd) throws IOException {
        Timer.Sample ackTimer = Timer.start(); // server side of send-to-ack (ADR-0009); acks only
        UUID userId = (UUID) session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID);
        String reply;
        boolean acked = false;
        try {
            MessageResponse saved = messageService.sendMessage(
                    cmd.conversationId(), userId, new SendMessageRequest(cmd.clientMsgId(), cmd.body()),
                    session.getId());
            reply = codec.ack(saved);
            acked = true;
        } catch (ApiException e) {
            reply = codec.errorFor(cmd.clientMsgId(), e);
        } catch (RuntimeException e) {
            log.error("WebSocket send failed on session {}", session.getId(), e); // never logs the body
            reply = codec.errorFor(cmd.clientMsgId(), e);
        }
        reply(session, reply);
        if (acked) {
            ackTimer.stop(metrics.sendAckTimer());
        }
    }

    /**
     * Cursor frame (ADR-0007): same checks and guarded update as the REST endpoint. No reply on success
     * (the pushed cursor frame to the user's other sockets is the confirmation); any error becomes an
     * error frame with clientMsgId null.
     */
    private void handleCursor(WebSocketSession session, CursorCommand cmd) {
        UUID userId = (UUID) session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID);
        try {
            conversationService.advanceCursor(cmd.conversationId(), userId, cmd.kind(), cmd.seq(), session.getId());
        } catch (ApiException e) {
            reply(session, codec.errorFor(null, e));
        } catch (RuntimeException e) {
            log.error("WebSocket cursor update failed on session {}", session.getId(), e);
            reply(session, codec.errorFor(null, e));
        }
    }

    /**
     * Replies (acks, error frames) go through the registry's per-session decorator, like pushes, so
     * concurrent sends to one socket are serialised and bounded.
     */
    private void reply(WebSocketSession session, String frame) {
        UUID userId = (UUID) session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID);
        if (!registry.sendTo(userId, session.getId(), new TextMessage(frame))) {
            log.debug("Reply not sent: WebSocket session {} is no longer registered", session.getId());
        }
    }

    /** A pong (answer to the heartbeat ping) proves the connection is alive. */
    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        heartbeat.onPong(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        unregister(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        metrics.wsClosed(status.getCode()); // called once per session, with the code that closed it
        unregister(session);
    }

    private void unregister(WebSocketSession session) {
        heartbeat.forget(session.getId());
        if (session.getAttributes().get(ATTR_LIFETIME_CLOSE) instanceof ScheduledFuture<?> lifetimeClose) {
            lifetimeClose.cancel(false); // a socket closed early must not leave its close task queued
        }
        if (session.getAttributes().get(WsAuthHandshakeInterceptor.ATTR_USER_ID) instanceof UUID userId) {
            registry.unregister(userId, session); // idempotent: transport error and close may both arrive
        }
    }
}
