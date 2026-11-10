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

    /**
     * Username prefix search (ADR-0012). {@code :pattern} is the already escaped, lower-cased prefix plus '%'.
     * {@code lower(username::text) LIKE ...} matches the index idx_users_username_prefix (text_pattern_ops).
     * Excludes the searcher, anonymized accounts, bots, and users who blocked the searcher. Never selects email.
     */
    public static final String SEARCH_SQL = """
            SELECT u.id, u.username, u.display_name, u.is_guest
            FROM users u
            WHERE lower(u.username::text) LIKE :pattern ESCAPE '\\'
              AND u.id <> :me
              AND u.deleted_at IS NULL
              AND NOT u.is_bot
              AND NOT EXISTS (SELECT 1 FROM user_blocks b WHERE b.blocker_id = u.id AND b.blocked_id = :me)
            ORDER BY lower(u.username::text)
            LIMIT :limit
            """;

    public UserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** @param pattern escaped, lower-cased prefix followed by '%' (see {@link #SEARCH_SQL}) */
    public java.util.List<com.chatplatform.user.dto.UserSearchResult> search(String pattern, UUID me, int limit) {
        return jdbcClient.sql(SEARCH_SQL)
                .param("pattern", pattern).param("me", me).param("limit", limit)
                .query((rs, n) -> new com.chatplatform.user.dto.UserSearchResult(
                        rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getBoolean("is_guest")))
                .list();
    }

    /** Public summaries (no email) for the given ids, in one query. Unknown ids are simply absent. */
    public java.util.List<com.chatplatform.user.dto.UserSummary> summaries(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return java.util.List.of();
        }
        return jdbcClient.sql("""
                        SELECT id, username, display_name, is_guest, is_bot, deleted_at IS NOT NULL AS deleted
                        FROM users WHERE id IN (:ids)
                        """)
                .param("ids", ids)
                .query((rs, n) -> new com.chatplatform.user.dto.UserSummary(
                        rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
                        rs.getBoolean("is_guest"), rs.getBoolean("is_bot"), rs.getBoolean("deleted")))
                .list();
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

    /** Replaces the password hash; true if the user exists. */
    public boolean updatePasswordHash(UUID id, String passwordHash) {
        return jdbcClient.sql("UPDATE users SET password_hash = :hash, updated_at = now() WHERE id = :id")
                .param("hash", passwordHash)
                .param("id", id)
                .update() == 1;
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
