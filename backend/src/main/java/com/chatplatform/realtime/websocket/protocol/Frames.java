package com.chatplatform.realtime.websocket.protocol;

import com.chatplatform.message.dto.MessageResponse;
import org.springframework.web.socket.CloseStatus;

import java.time.Instant;
import java.util.UUID;

/** Frame protocol v1 (ADR-0006): the parsed inbound command and the outbound frame shapes. */
public final class Frames {

    public static final int VERSION = 1;

    private Frames() {
    }

    /** Result of parsing one inbound text frame. */
    public sealed interface Parsed permits SendCommand, Rejected, Violation {
    }

    /** A well-formed, valid {@code send} frame. */
    public record SendCommand(UUID conversationId, UUID clientMsgId, String body) implements Parsed {
    }

    /** Well-formed envelope but invalid content: answer with an error frame, keep the socket open. */
    public record Rejected(UUID clientMsgId, String code, String message) implements Parsed {
    }

    /** Protocol violation: close the socket with this status. */
    public record Violation(CloseStatus status) implements Parsed {
    }

    public record AckFrame(int v, String type, UUID clientMsgId, UUID messageId, UUID conversationId,
                           long seq, Instant createdAt) {
        public static AckFrame of(MessageResponse m) {
            return new AckFrame(VERSION, "ack", m.clientMsgId(), m.id(), m.conversationId(), m.seq(), m.createdAt());
        }
    }

    public record MessageFrame(int v, String type, MessageResponse message) {
        public static MessageFrame of(MessageResponse m) {
            return new MessageFrame(VERSION, "message", m);
        }
    }

    public record ErrorFrame(int v, String type, UUID clientMsgId, String code, String message) {
        public static ErrorFrame of(UUID clientMsgId, String code, String message) {
            return new ErrorFrame(VERSION, "error", clientMsgId, code, message);
        }
    }
}
