package com.chatplatform.realtime.websocket;

import org.springframework.web.socket.CloseStatus;

/** Application close codes sent by the server (4000-4999 is the range reserved for applications). */
public final class WsCloseStatuses {

    /** The socket reached ws.session.max-lifetime: reconnect with a fresh ticket. */
    public static final CloseStatus SESSION_LIFETIME_REACHED = new CloseStatus(4001, "session lifetime reached");

    /** The user's refresh-token family was revoked (replay / theft signal): log in again. */
    public static final CloseStatus CREDENTIALS_REVOKED = new CloseStatus(4002, "credentials revoked");

    /** The socket could not keep up (send-time or buffer limit exceeded, or a failed write): recover from history. */
    public static final CloseStatus SLOW_CONSUMER = new CloseStatus(4003, "slow consumer");

    /** No pong or frame within ws.heartbeat.timeout: the connection is presumed dead; reconnect. */
    public static final CloseStatus IDLE_TIMEOUT = new CloseStatus(4006, "idle timeout");

    /** The user already has ws.max-sockets-per-user open sockets: this new one is refused. Don't auto-reconnect. */
    public static final CloseStatus TOO_MANY_SOCKETS = new CloseStatus(4007, "too many sockets");

    /** The guest account expired and was removed (ADR-0012). Don't auto-reconnect; offer a new demo or sign-up. */
    public static final CloseStatus GUEST_EXPIRED = new CloseStatus(4008, "guest expired");

    /** The frame's "v" is not a protocol version this server speaks. */
    public static final CloseStatus UNSUPPORTED_PROTOCOL_VERSION = new CloseStatus(4004, "unsupported protocol version");

    /** The frame's "type" is not defined in this protocol version (a client bug; versioning handles evolution). */
    public static final CloseStatus UNKNOWN_FRAME_TYPE = new CloseStatus(4005, "unknown frame type");

    private WsCloseStatuses() {
    }
}
