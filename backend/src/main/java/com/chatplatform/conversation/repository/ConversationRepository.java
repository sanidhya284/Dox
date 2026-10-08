package com.chatplatform.conversation.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
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

    public Optional<Conversation> findById(UUID id) {
        return jdbcClient.sql("SELECT * FROM conversations WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
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
                .param("now", Instant.now())
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
}
