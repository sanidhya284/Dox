package com.chatplatform.auth.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RefreshTokenRepository {

    private final JdbcClient jdbcClient;

    private static final RowMapper<RefreshToken> MAPPER = (rs, rowNum) -> new RefreshToken(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("token_hash"),
            rs.getObject("family_id", UUID.class),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("revoked_at") != null ? rs.getTimestamp("revoked_at").toInstant() : null,
            rs.getTimestamp("created_at").toInstant()
    );

    public RefreshTokenRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(RefreshToken token) {
        jdbcClient.sql("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, expires_at)
                VALUES (COALESCE(:id, gen_random_uuid()), :userId, :tokenHash, :familyId, :expiresAt)
                """)
                .param("id", token.id())
                .param("userId", token.userId())
                .param("tokenHash", token.tokenHash())
                .param("familyId", token.familyId())
                .param("expiresAt", token.expiresAt() == null ? null : OffsetDateTime.ofInstant(token.expiresAt(), ZoneOffset.UTC))
                .update();
    }

    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jdbcClient.sql("SELECT * FROM refresh_tokens WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query(MAPPER)
                .optional();
    }

    public void revoke(String tokenHash) {
        jdbcClient.sql("UPDATE refresh_tokens SET revoked_at = :now WHERE token_hash = :tokenHash")
                .param("now", OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
                .param("tokenHash", tokenHash)
                .update();
    }

    public void revokeFamily(UUID familyId) {
        jdbcClient.sql("UPDATE refresh_tokens SET revoked_at = :now WHERE family_id = :familyId")
                .param("now", OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
                .param("familyId", familyId)
                .update();
    }
}
