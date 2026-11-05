package com.chatplatform.realtime.websocket.protocol;

import com.chatplatform.message.dto.MessageResponse;
import com.chatplatform.realtime.websocket.WsCloseStatuses;
import com.chatplatform.realtime.websocket.protocol.Frames.AckFrame;
import com.chatplatform.realtime.websocket.protocol.Frames.CursorCommand;
import com.chatplatform.realtime.websocket.protocol.Frames.CursorFrame;
import com.chatplatform.realtime.websocket.protocol.Frames.ErrorFrame;
import com.chatplatform.realtime.websocket.protocol.Frames.MessageFrame;
import com.chatplatform.realtime.websocket.protocol.Frames.Parsed;
import com.chatplatform.realtime.websocket.protocol.Frames.Rejected;
import com.chatplatform.realtime.websocket.protocol.Frames.SendCommand;
import com.chatplatform.realtime.websocket.protocol.Frames.Violation;
import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.util.UUID;

/**
 * Parses inbound frames and writes outbound frames for protocol v1 (ADR-0006), using Spring's
 * ObjectMapper bean (ISO-8601 dates).
 *
 * <p>Error-vs-close rule: if the envelope ({@code v}, {@code type}) or a present {@code clientMsgId}
 * cannot be parsed, the frame is a protocol violation and the socket is closed (1007, or 4004/4005).
 * If the envelope is fine but the content is invalid, the client gets an error frame carrying its
 * {@code clientMsgId} (null when it was missing) and the socket stays open.
 */
@Component
public class FrameCodec {

    /** Same code and message as the REST 500 path (GlobalExceptionHandler): reveals nothing. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred";

    private static final String VALIDATION_ERROR = ErrorCode.VALIDATION_ERROR.name();

    private final ObjectMapper mapper;

    public FrameCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Parsed parse(String payload) {
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (JsonProcessingException e) {
            return violation(CloseStatus.BAD_DATA);
        }
        if (root == null || !root.isObject()) {
            return violation(CloseStatus.BAD_DATA);
        }
        JsonNode v = root.get("v");
        if (v == null || !v.isInt()) {
            return violation(CloseStatus.BAD_DATA);
        }
        if (v.intValue() != Frames.VERSION) {
            return violation(WsCloseStatuses.UNSUPPORTED_PROTOCOL_VERSION);
        }
        JsonNode type = root.get("type");
        if (type == null || !type.isTextual()) {
            return violation(CloseStatus.BAD_DATA);
        }
        return switch (type.textValue()) {
            case "send" -> parseSend(root);
            case "cursor" -> parseCursor(root);
            default -> violation(WsCloseStatuses.UNKNOWN_FRAME_TYPE);
        };
    }

    /**
     * {@code {"v":1,"type":"cursor","conversationId","kind","seq"}} (ADR-0007). There is no clientMsgId,
     * so errors carry {@code clientMsgId: null}. Only shapes are checked here; the kind's value and the
     * seq range are checked by ConversationService after membership.
     */
    private Parsed parseCursor(JsonNode root) {
        UUID conversationId = uuidOrNull(root.get("conversationId"));
        if (conversationId == null) {
            return new Rejected(null, VALIDATION_ERROR, "conversationId must be a UUID");
        }
        JsonNode kind = root.get("kind");
        if (kind == null || !kind.isTextual()) {
            return new Rejected(null, VALIDATION_ERROR, "kind must be \"delivered\" or \"read\"");
        }
        JsonNode seq = root.get("seq");
        if (seq == null || !seq.isIntegralNumber() || !seq.canConvertToLong()) {
            return new Rejected(null, VALIDATION_ERROR, "seq must be an integer");
        }
        return new CursorCommand(conversationId, kind.textValue(), seq.longValue());
    }

    private Parsed parseSend(JsonNode root) {
        JsonNode cmidNode = root.get("clientMsgId");
        UUID clientMsgId = null;
        if (cmidNode != null && !cmidNode.isNull()) {
            clientMsgId = uuidOrNull(cmidNode);
            if (clientMsgId == null) {
                return violation(CloseStatus.BAD_DATA); // present but unparseable: can't correlate a reply
            }
        }
        if (clientMsgId == null) {
            return new Rejected(null, VALIDATION_ERROR, "clientMsgId is required");
        }
        UUID conversationId = uuidOrNull(root.get("conversationId"));
        if (conversationId == null) {
            return new Rejected(clientMsgId, VALIDATION_ERROR, "conversationId must be a UUID");
        }
        JsonNode body = root.get("body");
        if (body == null || !body.isTextual() || body.textValue().isBlank()) {
            return new Rejected(clientMsgId, VALIDATION_ERROR, "body must not be blank");
        }
        // The length limit is enforced by MessageService for every caller (REST and WebSocket).
        return new SendCommand(conversationId, clientMsgId, body.textValue());
    }

    private static UUID uuidOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(node.textValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Violation violation(CloseStatus status) {
        return new Violation(status);
    }

    public String ack(MessageResponse m) {
        return write(AckFrame.of(m));
    }

    public String message(MessageResponse m) {
        return write(MessageFrame.of(m));
    }

    public String cursor(UUID conversationId, UUID userId, long lastDeliveredSeq, long lastReadSeq) {
        return write(CursorFrame.of(conversationId, userId, lastDeliveredSeq, lastReadSeq));
    }

    public String error(Rejected rejected) {
        return write(ErrorFrame.of(rejected.clientMsgId(), rejected.code(), rejected.message()));
    }

    /**
     * Error frame for an exception from the send path. Domain errors keep their code; the message is
     * the code's fixed default, except VALIDATION_ERROR, whose detail only describes the client's own
     * input. Anything else is the generic INTERNAL_ERROR.
     */
    public String errorFor(UUID clientMsgId, Throwable t) {
        if (t instanceof ApiException api) {
            ErrorCode code = api.getErrorCode();
            String message = (code == ErrorCode.VALIDATION_ERROR) ? api.getMessage() : code.defaultMessage;
            return write(ErrorFrame.of(clientMsgId, code.name(), message));
        }
        return write(ErrorFrame.of(clientMsgId, INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE));
    }

    private String write(Object frame) {
        try {
            return mapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise frame " + frame.getClass().getSimpleName(), e);
        }
    }
}
