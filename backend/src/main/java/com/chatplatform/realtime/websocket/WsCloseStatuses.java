package com.chatplatform.realtime.websocket;

import org.springframework.web.socket.CloseStatus;

/** Application close codes sent by the server (4000-4999 is the range reserved for applications). */
public final class WsCloseStatuses {

    /** The socket reached ws.session.max-lifetime: reconnect with a fresh ticket. */
    public static final CloseStatus SESSION_LIFETIME_REACHED = new CloseStatus(4001, "session lifetime reached");

    /** The user's refresh-token family was revoked (replay / theft signal): log in again. */
    public static final CloseStatus CREDENTIALS_REVOKED = new CloseStatus(4002, "credentials revoked");

    private WsCloseStatuses() {
    }
}
