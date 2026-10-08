-- V3__create_refresh_tokens.sql
-- ─────────────────────────────────────────────────────────────────────────────
-- Refresh token storage.
--
-- Security properties:
--   - The raw token (a random UUID) is NEVER stored.
--   - Only SHA-256(rawToken) is stored.  If this table is compromised,
--     the attacker cannot use the hashes to authenticate.
--
-- Token rotation + theft detection via family_id:
--   Each login creates a new family.  Each refresh rotates the token within
--   the same family, revoking the previous one.  If a revoked token is
--   presented, the server revokes the ENTIRE family — both the attacker
--   and the legitimate user are logged out, signalling a theft event.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE refresh_tokens (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  TEXT        NOT NULL,
    family_id   UUID        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_refresh_tokens           PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_hash      UNIQUE (token_hash)
);

-- Supports: revoke all tokens for a user (deactivation, password change)
CREATE INDEX idx_refresh_tokens_user_id   ON refresh_tokens (user_id);

-- Supports: revoke entire family on theft detection
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
