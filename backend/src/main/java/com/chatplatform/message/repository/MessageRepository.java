package com.chatplatform.message.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MessageRepository {

    private final JdbcClient jdbcClient;

    public static final RowMapper<Message> MAPPER = (rs, rowNum) -> new Message(
            rs.getObject("id", UUID.class),
            rs.getObject("conversation_id", UUID.class),
            rs.getLong("seq"),
            rs.getObject("sender_id", UUID.class),
            rs.getObject("client_msg_id", UUID.class),
            rs.getString("body"),
            rs.getTimestamp("created_at").toInstant()
    );

    public MessageRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Message> findByClientMsgId(UUID senderId, UUID clientMsgId) {
        return jdbcClient.sql("""
                SELECT * FROM messages
                WHERE sender_id = :senderId AND client_msg_id = :clientMsgId
                """)
                .param("senderId", senderId)
                .param("clientMsgId", clientMsgId)
                .query(MAPPER)
                .optional();
    }

    public Optional<Message> insert(UUID conversationId, long seq, UUID senderId, UUID clientMsgId, String body) {
        return jdbcClient.sql("""
                INSERT INTO messages (id, conversation_id, seq, sender_id, client_msg_id, body)
                VALUES (gen_random_uuid(), :conversationId, :seq, :senderId, :clientMsgId, :body)
                ON CONFLICT (sender_id, client_msg_id) DO NOTHING
                RETURNING *
                """)
                .param("conversationId", conversationId)
                .param("seq", seq)
                .param("senderId", senderId)
                .param("clientMsgId", clientMsgId)
                .param("body", body)
                .query(MAPPER)
                .optional();
    }
    
    public List<Message> findMessages(UUID conversationId, long beforeSeq, int limit) {
        return jdbcClient.sql("""
                SELECT * FROM messages
                WHERE conversation_id = :conversationId AND seq < :beforeSeq
                ORDER BY seq DESC
                LIMIT :limit
                """)
                .param("conversationId", conversationId)
                .param("beforeSeq", beforeSeq)
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }
}
