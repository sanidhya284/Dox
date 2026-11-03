package com.chatplatform.user.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcClient jdbcClient;

    private static final RowMapper<User> USER_ROW_MAPPER = (rs, rowNum) -> new User(
            rs.getObject("id", UUID.class),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public UserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<User> findById(UUID id) {
        return jdbcClient.sql("SELECT * FROM users WHERE id = :id")
                .param("id", id)
                .query(USER_ROW_MAPPER)
                .optional();
    }

    public Optional<User> findByUsername(String username) {
        // citext makes this case-insensitive automatically at the DB layer
        return jdbcClient.sql("SELECT * FROM users WHERE username = CAST(:username AS citext)")
                .param("username", username)
                .query(USER_ROW_MAPPER)
                .optional();
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbcClient.sql("SELECT 1 FROM users WHERE username = CAST(:username AS citext)")
                .param("username", username)
                .query(Integer.class)
                .optional()
                .orElse(0);
        return count == 1;
    }

    public boolean existsByEmail(String email) {
        Integer count = jdbcClient.sql("SELECT 1 FROM users WHERE email = CAST(:email AS citext)")
                .param("email", email)
                .query(Integer.class)
                .optional()
                .orElse(0);
        return count == 1;
    }

    public User save(User user) {
        // If ID is null, the DB gen_random_uuid() generates it, but JdbcClient 
        // doesn't easily fetch generated UUIDs without a KeyHolder.
        // For simplicity, if id is null, we can generate it in code, or we can use RETURNING.
        // Using RETURNING is best practice in PostgreSQL.
        return jdbcClient.sql("""
                INSERT INTO users (id, username, email, password_hash, display_name)
                VALUES (COALESCE(:id, gen_random_uuid()), :username, :email, :passwordHash, :displayName)
                RETURNING *
                """)
                .param("id", user.id())
                .param("username", user.username())
                .param("email", user.email())
                .param("passwordHash", user.passwordHash())
                .param("displayName", user.displayName())
                .query(USER_ROW_MAPPER)
                .single();
    }
}
