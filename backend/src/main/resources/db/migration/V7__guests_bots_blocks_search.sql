-- Phase 6b (ADR-0012): guest and bot accounts, account removal, blocks, username prefix search.
-- Additive only (ADR-0011 Decision 10): new nullable or defaulted columns, a new table, new indexes.
-- The previous release reads users by column name and inserts with these defaults, so a rollback still works.

ALTER TABLE users ADD COLUMN is_guest   BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN is_bot     BOOLEAN NOT NULL DEFAULT false;
-- Set when an account is anonymized (expired guest, removed abusive account); the row stays because
-- messages.sender_id and conversations.created_by reference it.
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ NULL;

CREATE TABLE user_blocks (
    blocker_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_blocks PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT chk_user_blocks_not_self CHECK (blocker_id <> blocked_id)
);
CREATE INDEX idx_user_blocks_blocked ON user_blocks (blocked_id);

-- Prefix search on usernames: LIKE 'abc%' on lower(username) can use this index (text_pattern_ops).
CREATE INDEX idx_users_username_prefix ON users (lower(username::text) text_pattern_ops);

-- The guest cleanup scans only live guests, oldest first.
CREATE INDEX idx_users_live_guests ON users (created_at) WHERE is_guest AND deleted_at IS NULL;
