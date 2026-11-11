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
            rs.getTimestamp("updated_at").toInstant(),
            rs.getBoolean("is_guest"),
            rs.getBoolean("is_bot"),
            rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toInstant() : null
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

    /**
     * The only accounts that may log in with a password (ADR-0012): not a guest, not a bot, not removed.
     * Everything else looks exactly like an unknown username to login.
     */
    public Optional<User> findLoginCandidate(String username) {
        return jdbcClient.sql("""
                        SELECT * FROM users
                        WHERE username = CAST(:username AS citext)
                          AND NOT is_guest AND NOT is_bot AND deleted_at IS NULL
                        """)
                .param("username", username)
                .query(USER_ROW_MAPPER)
                .optional();
    }

    /** Idempotent. */
    public void block(UUID blocker, UUID blocked) {
        jdbcClient.sql("""
                        INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (:blocker, :blocked)
                        ON CONFLICT DO NOTHING
                        """)
                .param("blocker", blocker).param("blocked", blocked).update();
    }

    public void unblock(UUID blocker, UUID blocked) {
        jdbcClient.sql("DELETE FROM user_blocks WHERE blocker_id = :blocker AND blocked_id = :blocked")
                .param("blocker", blocker).param("blocked", blocked).update();
    }

    public java.util.List<com.chatplatform.user.dto.UserSearchResult> blockedBy(UUID blocker) {
        return jdbcClient.sql("""
                        SELECT u.id, u.username, u.display_name, u.is_guest
                        FROM user_blocks b JOIN users u ON u.id = b.blocked_id
                        WHERE b.blocker_id = :blocker
                        ORDER BY b.created_at
                        """)
                .param("blocker", blocker)
                .query((rs, n) -> new com.chatplatform.user.dto.UserSearchResult(
                        rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getBoolean("is_guest")))
                .list();
    }

    /** True if either user blocked the other. */
    public boolean blockedEitherWay(UUID a, UUID b) {
        return jdbcClient.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_blocks
                                       WHERE (blocker_id = :a AND blocked_id = :b)
                                          OR (blocker_id = :b AND blocked_id = :a))
                        """)
                .param("a", a).param("b", b).query(Boolean.class).single();
    }

    /** One query for a DM send: the peer was removed, or either side blocked the other. */
    public boolean directPeerUnavailable(UUID sender, UUID peer) {
        return jdbcClient.sql("""
                        SELECT EXISTS (SELECT 1 FROM users WHERE id = :peer AND deleted_at IS NOT NULL)
                            OR EXISTS (SELECT 1 FROM user_blocks
                                       WHERE (blocker_id = :sender AND blocked_id = :peer)
                                          OR (blocker_id = :peer AND blocked_id = :sender))
                        """)
                .param("sender", sender).param("peer", peer).query(Boolean.class).single();
    }

    /** [isGuest, isDeleted] in one primary-key lookup; empty if the user doesn't exist. */
    public Optional<boolean[]> guestAndDeleted(UUID id) {
        return jdbcClient.sql("SELECT is_guest, deleted_at IS NOT NULL AS deleted FROM users WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new boolean[]{rs.getBoolean("is_guest"), rs.getBoolean("deleted")})
                .optional();
    }

    public boolean isGuest(UUID id) {
        return jdbcClient.sql("SELECT is_guest FROM users WHERE id = :id").param("id", id)
                .query(Boolean.class).optional().orElse(false);
    }

    public long countLiveGuests() {
        return jdbcClient.sql("SELECT count(*) FROM users WHERE is_guest AND deleted_at IS NULL")
                .query(Long.class).single();
    }

    /**
     * Claims the oldest live guest created before {@code cutoff}, row-locked until the caller's transaction ends.
     * SKIP LOCKED: a row another instance (or a concurrent run) is already removing is skipped, never waited on.
     */
    public Optional<UUID> lockNextExpiredGuest(java.time.Instant cutoff) {
        return jdbcClient.sql("""
                        SELECT id FROM users
                        WHERE is_guest AND deleted_at IS NULL AND created_at < :cutoff
                        ORDER BY created_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("cutoff", java.time.OffsetDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC))
                .query(UUID.class)
                .optional();
    }

    /** Locks the account row (waits if another transaction holds it). Empty if absent or already removed. */
    public Optional<UUID> lockLiveByUsername(String username) {
        return jdbcClient.sql("""
                        SELECT id FROM users
                        WHERE username = CAST(:username AS citext) AND deleted_at IS NULL
                        FOR UPDATE
                        """)
                .param("username", username)
                .query(UUID.class)
                .optional();
    }

    /**
     * Scrubs personal data (ADR-0012). The username and email are derived from the id, so they are unique; they
     * use the reserved "deleted-" prefix. The password hash becomes a value BCrypt never matches.
     */
    public void anonymize(UUID id, String displayName) {
        String hex = id.toString().replace("-", "");
        jdbcClient.sql("""
                        UPDATE users
                        SET username = :username, email = :email, display_name = :displayName,
                            password_hash = '!removed', deleted_at = now(), updated_at = now()
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .param("username", "deleted-" + hex)
                .param("email", "deleted-" + hex + "@deleted.invalid")
                .param("displayName", displayName)
                .param("id", id)
                .update();
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
                INSERT INTO users (id, username, email, password_hash, display_name, is_guest, is_bot)
                VALUES (COALESCE(:id, gen_random_uuid()), :username, :email, :passwordHash, :displayName,
                        :isGuest, :isBot)
                RETURNING *
                """)
                .param("id", user.id())
                .param("username", user.username())
                .param("email", user.email())
                .param("passwordHash", user.passwordHash())
                .param("displayName", user.displayName())
                .param("isGuest", user.isGuest())
                .param("isBot", user.isBot())
                .query(USER_ROW_MAPPER)
                .single();
    }
}
