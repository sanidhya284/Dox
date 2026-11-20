package com.chatplatform.realtime.bus;

import java.util.List;
import java.util.UUID;

/**
 * One cross-instance delivery (ADR-0013), published on the Redis channel {@code chat:rt}.
 * <ul>
 *   <li>{@code message} / {@code cursor}: push {@code frame} to every socket of {@code userIds}, except the socket
 *       {@code excludeSessionId} (the origin socket, which got the ack);</li>
 *   <li>{@code close_user}: close every socket of {@code userIds} with {@code closeCode} / {@code closeReason}.</li>
 * </ul>
 * {@code origin} is the publishing instance: it already delivered locally and ignores its own echo.
 */
public record Envelope(String origin, BusKind kind, List<UUID> userIds, String frame, String excludeSessionId,
                       Integer closeCode, String closeReason) {

    public static Envelope push(String origin, BusKind kind, List<UUID> userIds, String frame, String excludeSessionId) {
        return new Envelope(origin, kind, userIds, frame, excludeSessionId, null, null);
    }

    public static Envelope closeUser(String origin, UUID userId, int code, String reason) {
        return new Envelope(origin, BusKind.close_user, List.of(userId), null, null, code, reason);
    }
}
