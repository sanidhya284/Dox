package com.chatplatform.conversation.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ParticipantRepository {

    private final JdbcClient jdbcClient;

    public static final RowMapper<ConversationParticipant> MAPPER = (rs, rowNum) -> new ConversationParticipant(
            rs.getObject("conversation_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("role"),
            rs.getTimestamp("joined_at").toInstant(),
            rs.getTimestamp("left_at") != null ? rs.getTimestamp("left_at").toInstant() : null,
            rs.getLong("last_delivered_seq"),
            rs.getLong("last_read_seq")
    );

    public ParticipantRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(UUID conversationId, UUID userId, String role) {
        jdbcClient.sql("""
                INSERT INTO conversation_participants (conversation_id, user_id, role)
                VALUES (:conversationId, :userId, :role)
                ON CONFLICT (conversation_id, user_id) DO NOTHING
                """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .param("role", role)
                .update();
    }

    public void insertMultiple(UUID conversationId, List<UUID> userIds, String role) {
        // Simple iteration for Phase 1. For very large groups, use batchUpdate.
        for (UUID userId : userIds) {
            insert(conversationId, userId, role);
        }
    }

    /** One membership row: who ever joined, and whether they have left. */
    public record Membership(UUID conversationId, UUID userId, boolean left) {
    }

    /** Every member who ever joined the given conversations (including those who left), in one query. */
    public List<Membership> findAllMembers(java.util.Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT conversation_id, user_id, left_at IS NOT NULL AS has_left
                        FROM conversation_participants
                        WHERE conversation_id IN (:ids)
                        ORDER BY joined_at, user_id
                        """)
                .param("ids", conversationIds)
                .query((rs, n) -> new Membership(rs.getObject("conversation_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getBoolean("has_left")))
                .list();
    }

    public List<UUID> findParticipantIdsByConversation(UUID conversationId) {
        return jdbcClient.sql("""
                SELECT user_id FROM conversation_participants
                WHERE conversation_id = :conversationId AND left_at IS NULL
                """)
                .param("conversationId", conversationId)
                .query(UUID.class)
                .list();
    }

    public Optional<ConversationParticipant> find(UUID conversationId, UUID userId) {
        return jdbcClient.sql("""
                SELECT * FROM conversation_participants
                WHERE conversation_id = :conversationId AND user_id = :userId
                """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(MAPPER)
                .optional();
    }

    public boolean isParticipant(UUID conversationId, UUID userId) {
        Integer count = jdbcClient.sql("""
                SELECT 1 FROM conversation_participants
                WHERE conversation_id = :conversationId AND user_id = :userId AND left_at IS NULL
                """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(Integer.class)
                .optional()
                .orElse(0);
        return count == 1;
    }

    // ── Cursors (ADR-0007) ────────────────────────────────────────────────────
    // The "only forward" guard is in the WHERE: a stale cursor matches no row and writes nothing
    // (no new row version). Concurrent updates serialise on the row lock and each re-checks the guard
    // against the latest committed row, so an older cursor can never overwrite a newer one.
    // Returns the number of rows updated: 1 = advanced, 0 = not advanced (or not an active participant).

    /** Read cursor to {@code seq}; read implies delivered. */
    public int advanceRead(UUID conversationId, UUID userId, long seq) {
        return jdbcClient.sql("""
                UPDATE conversation_participants
                   SET last_read_seq = :seq,
                       last_delivered_seq = GREATEST(last_delivered_seq, :seq)
                 WHERE conversation_id = :conversationId AND user_id = :userId AND left_at IS NULL
                   AND last_read_seq < :seq
                """)
                .param("seq", seq)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
    }

    public int advanceDelivered(UUID conversationId, UUID userId, long seq) {
        return jdbcClient.sql("""
                UPDATE conversation_participants
                   SET last_delivered_seq = :seq
                 WHERE conversation_id = :conversationId AND user_id = :userId AND left_at IS NULL
                   AND last_delivered_seq < :seq
                """)
                .param("seq", seq)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
    }

    /** Active participants (left_at IS NULL) with their cursors. */
    public List<ConversationParticipant> findActive(UUID conversationId) {
        return jdbcClient.sql("""
                SELECT * FROM conversation_participants
                WHERE conversation_id = :conversationId AND left_at IS NULL
                ORDER BY joined_at, user_id
                """)
                .param("conversationId", conversationId)
                .query(MAPPER)
                .list();
    }

    // ── Unread counts (read model) ────────────────────────────────────────────
    // Unread = messages after my read cursor that someone else sent. This joins the messages table
    // owned by the message module: a deliberate read-only query here, because ConversationService
    // cannot call MessageService (MessageService already depends on ConversationService). It uses the
    // (conversation_id, seq) index over the unread range only.

    public long unreadCount(UUID conversationId, UUID userId) {
        return jdbcClient.sql("""
                SELECT count(m.id)
                  FROM conversation_participants p
                  JOIN messages m ON m.conversation_id = p.conversation_id
                                 AND m.seq > p.last_read_seq
                                 AND m.sender_id <> p.user_id
                 WHERE p.conversation_id = :conversationId AND p.user_id = :userId
                """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /** Unread count per conversation for all of the user's conversations, in one query. */
    public Map<UUID, Long> unreadCountsForUser(UUID userId) {
        Map<UUID, Long> counts = new HashMap<>();
        jdbcClient.sql("""
                SELECT p.conversation_id, count(m.id) AS unread
                  FROM conversation_participants p
                  LEFT JOIN messages m ON m.conversation_id = p.conversation_id
                                      AND m.seq > p.last_read_seq
                                      AND m.sender_id <> p.user_id
                 WHERE p.user_id = :userId
                 GROUP BY p.conversation_id
                """)
                .param("userId", userId)
                .query((rs, n) -> counts.put(rs.getObject("conversation_id", UUID.class), rs.getLong("unread")))
                .list();
        return counts;
    }
}
