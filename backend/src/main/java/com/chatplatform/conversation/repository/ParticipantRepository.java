package com.chatplatform.conversation.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
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
}
