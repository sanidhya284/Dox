package com.chatplatform.realtime.service;

import com.chatplatform.realtime.dto.IssuedTicket;
import com.chatplatform.realtime.repository.WsTicketRepository;
import com.chatplatform.shared.metrics.ChatMetrics;
import com.chatplatform.shared.metrics.ChatMetrics.TicketOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and consumes one-time WebSocket authentication tickets.
 *
 * A ticket is a cryptographically random 32-byte value encoded as base64url
 * (no padding).  Only its SHA-256 hash is stored in {@code ws_tickets}.
 * {@link #consume} delegates to a single {@code DELETE … RETURNING} so the
 * ticket can only be used once, even under concurrent access.
 */
@Service
public class WsTicketService {

    private final WsTicketRepository wsTicketRepository;
    private final int ttlSeconds;
    private final ChatMetrics metrics;
    private final SecureRandom secureRandom = new SecureRandom();

    public WsTicketService(
            WsTicketRepository wsTicketRepository,
            @Value("${ws.ticket.ttl-seconds:30}") int ttlSeconds,
            ChatMetrics metrics) {
        this.metrics = metrics;
        this.wsTicketRepository = wsTicketRepository;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Issues a new one-time ticket for the given user.
     * Expired tickets are cleaned up opportunistically before inserting.
     *
     * @param userId the authenticated user who will use this ticket
     * @return the raw base64url ticket and its TTL in seconds
     */
    public IssuedTicket issue(UUID userId) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        // No padding: base64url without '=' so it is safe in a URL query parameter
        String rawTicket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = sha256Hex(rawTicket);

        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusSeconds(ttlSeconds);

        // Clean expired rows before inserting to keep the table small
        wsTicketRepository.deleteExpired(now);
        wsTicketRepository.insert(hash, userId, expiresAt);
        metrics.ticket(TicketOutcome.ISSUED);

        return new IssuedTicket(rawTicket, ttlSeconds);
    }

    /**
     * Consumes the ticket identified by {@code rawTicket}.
     * Returns the associated user id if the ticket exists and has not expired;
     * empty otherwise.  A ticket can only be consumed once.
     *
     * @param rawTicket the raw base64url value received from the client
     * @return the user id the ticket was issued for, or empty
     */
    public Optional<UUID> consume(String rawTicket) {
        String hash = sha256Hex(rawTicket);
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        Optional<UUID> userId = wsTicketRepository.consume(hash, now);
        metrics.ticket(userId.isPresent() ? TicketOutcome.CONSUMED : TicketOutcome.REJECTED);
        return userId;
    }

    /**
     * Computes the lowercase hex SHA-256 digest of {@code input}.
     * Intentionally private; the hashing logic is an implementation detail
     * of this class and must not be shared with TokenService.
     */
    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
