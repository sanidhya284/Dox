package com.chatplatform.realtime.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for one-time WebSocket authentication tickets.
 *
 * Only the SHA-256 hash of each ticket is stored; the raw value is never persisted.
 * The {@link #consume} method uses DELETE … RETURNING to atomically claim a ticket,
 * ensuring it can only be used once even under concurrent access.
 */
@Repository
public class WsTicketRepository {

    private final JdbcClient jdbcClient;

    public WsTicketRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Persists a new ticket (as its hash) for the given user.
     */
    public void insert(String hash, UUID userId, OffsetDateTime expiresAt) {
        jdbcClient.sql("""
                INSERT INTO ws_tickets (ticket_hash, user_id, expires_at)
                VALUES (:hash, :userId, :expiresAt)
                """)
                .param("hash", hash)
                .param("userId", userId)
                .param("expiresAt", expiresAt)
                .update();
    }

    /**
     * Deletes all rows whose {@code expires_at} is before {@code now}.
     * Called opportunistically on every {@code issue()} to avoid unbounded growth.
     */
    public void deleteForUser(UUID userId) {
        jdbcClient.sql("DELETE FROM ws_tickets WHERE user_id = :userId").param("userId", userId).update();
    }

    public void deleteExpired(OffsetDateTime now) {
        jdbcClient.sql("DELETE FROM ws_tickets WHERE expires_at <= :now")
                .param("now", now)
                .update();
    }

    /**
     * Atomically consumes the ticket identified by {@code hash}, returning the
     * associated user id if the ticket exists and has not yet expired.
     *
     * The single DELETE … RETURNING ensures that even if 20 threads call this
     * simultaneously with the same hash, exactly one will see a non-empty result.
     */
    public Optional<UUID> consume(String hash, OffsetDateTime now) {
        return jdbcClient.sql("""
                DELETE FROM ws_tickets
                WHERE ticket_hash = :hash AND expires_at > :now
                RETURNING user_id
                """)
                .param("hash", hash)
                .param("now", now)
                .query((rs, rowNum) -> rs.getObject("user_id", UUID.class))
                .optional();
    }
}
