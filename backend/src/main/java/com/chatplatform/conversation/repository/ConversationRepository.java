package com.chatplatform.conversation.repository;

import com.chatplatform.conversation.dto.ConversationHead;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ConversationRepository {

    private final JdbcClient jdbcClient;

    public static final RowMapper<Conversation> MAPPER = (rs, rowNum) -> new Conversation(
            rs.getObject("id", UUID.class),
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("direct_key"),
            rs.getObject("created_by", UUID.class),
            rs.getLong("last_seq"),
            rs.getTimestamp("last_message_at") != null ? rs.getTimestamp("last_message_at").toInstant() : null,
            rs.getTimestamp("created_at").toInstant()
    );

    public ConversationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Conversation insertDirect(UUID creatorId, String directKey) {
        // ON CONFLICT … DO UPDATE SET direct_key = EXCLUDED.direct_key is a PostgreSQL trick:
        // DO NOTHING returns 0 rows so RETURNING * would fail, but DO UPDATE (even a no-op)
        // always returns the final row.  The UPDATE is idempotent — setting direct_key to its
        // own value changes nothing but guarantees RETURNING * works on first-create AND retry.
        return jdbcClient.sql("""
                INSERT INTO conversations (id, type, direct_key, created_by)
                VALUES (gen_random_uuid(), 'DIRECT', :directKey, :createdBy)
                ON CONFLICT (direct_key) DO UPDATE SET direct_key = EXCLUDED.direct_key
                RETURNING *
                """)
                .param("directKey", directKey)
                .param("createdBy", creatorId)
                .query(MAPPER)
                .single();
    }

    public Conversation insertGroup(UUID creatorId, String title) {
        return jdbcClient.sql("""
                INSERT INTO conversations (id, type, title, created_by)
                VALUES (gen_random_uuid(), 'GROUP', :title, :createdBy)
                RETURNING *
                """)
                .param("title", title)
                .param("createdBy", creatorId)
                .query(MAPPER)
                .single();
    }

    /**
     * The newest message of each given conversation, for the list preview (ADR-0012). Joins on
     * {@code m.seq = c.last_seq}: an exact lookup on the unique (conversation_id, seq) index, no sort or scan of
     * the conversation's history. Conversations with no message are absent from the map.
     */
    public java.util.Map<UUID, com.chatplatform.conversation.dto.LastMessage> lastMessages(
            java.util.Collection<UUID> conversationIds) {
        java.util.Map<UUID, com.chatplatform.conversation.dto.LastMessage> out = new java.util.HashMap<>();
        if (conversationIds.isEmpty()) {
            return out;
        }
        jdbcClient.sql("""
                        SELECT c.id AS conversation_id, m.sender_id, m.body, m.created_at, m.seq
                        FROM conversations c
                        JOIN messages m ON m.conversation_id = c.id AND m.seq = c.last_seq
                        WHERE c.id IN (:ids)
                        """)
                .param("ids", conversationIds)
                .query((rs, n) -> {
                    out.put(rs.getObject("conversation_id", UUID.class),
                            new com.chatplatform.conversation.dto.LastMessage(
                                    rs.getObject("sender_id", UUID.class),
                                    com.chatplatform.conversation.dto.LastMessage.preview(rs.getString("body")),
                                    rs.getTimestamp("created_at").toInstant(),
                                    rs.getLong("seq")));
                    return null;
                })
                .list();
        return out;
    }

    /** Deletes conversations (participants cascade). The caller deletes their messages first (no cascade). */
    public void deleteByIds(java.util.Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbcClient.sql("DELETE FROM conversations WHERE id IN (:ids)").param("ids", ids).update();
        }
    }

    public Optional<Conversation> findById(UUID id) {
        return jdbcClient.sql("SELECT * FROM conversations WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public void lockForUpdate(UUID id) {
        jdbcClient.sql("SELECT id FROM conversations WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(UUID.class)
                .optional();
    }

    public long incrementAndGetLastSeq(UUID conversationId) {
        return jdbcClient.sql("""
                UPDATE conversations
                SET last_seq = last_seq + 1, last_message_at = :now
                WHERE id = :id
                RETURNING last_seq
                """)
                .param("id", conversationId)
                .param("now", OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    /**
     * Returns all conversations the given user is an active participant in,
     * ordered by most-recently-messaged first.
     *
     * Ordering by last_message_at DESC NULLS LAST puts conversations that have
     * never had a message at the bottom, behind all conversations with messages.
     */
    public List<Conversation> findByUserId(UUID userId) {
        return jdbcClient.sql("""
                SELECT c.*
                FROM conversations c
                JOIN conversation_participants cp ON c.id = cp.conversation_id
                WHERE cp.user_id = :userId AND cp.left_at IS NULL
                ORDER BY c.last_message_at DESC NULLS LAST, c.created_at DESC
                """)
                .param("userId", userId)
                .query(MAPPER)
                .list();
    }

    /**
     * One row per conversation the user is active in: its id and last_seq. A single join used by
     * client anti-entropy polling (ADR-0008); much cheaper than the full conversation list.
     */
    public List<ConversationHead> findHeadsForUser(UUID userId) {
        return jdbcClient.sql("""
                SELECT c.id, c.last_seq
                  FROM conversation_participants p
                  JOIN conversations c ON c.id = p.conversation_id
                 WHERE p.user_id = :userId AND p.left_at IS NULL
                """)
                .param("userId", userId)
                .query((rs, n) -> new ConversationHead(rs.getObject("id", UUID.class), rs.getLong("last_seq")))
                .list();
    }
}
