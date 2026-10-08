-- V4__create_conversations.sql
-- ─────────────────────────────────────────────────────────────────────────────
-- Conversations and their participants.
--
-- Direct conversation deduplication via direct_key:
--   For a DM between userA and userB, the key is always:
--       min(userA_id, userB_id) || ':' || max(userA_id, userB_id)
--   The UNIQUE constraint on direct_key means concurrent requests to create
--   the same DM resolve at the database — only one row is ever created.
--   Application uses: INSERT ... ON CONFLICT (direct_key) DO NOTHING RETURNING *
--
-- Sequence numbers:
--   last_seq tracks the most recently assigned message sequence for each
--   conversation.  Message sends do:
--       UPDATE conversations SET last_seq = last_seq + 1 RETURNING last_seq
--   This UPDATE acquires a row-level lock, serialising concurrent senders
--   within the same conversation without application-level locking.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TYPE conversation_type AS ENUM ('DIRECT', 'GROUP');

CREATE TABLE conversations (
    id              UUID              NOT NULL DEFAULT gen_random_uuid(),
    type            conversation_type NOT NULL,
    title           TEXT,
    direct_key      TEXT,
    created_by      UUID              NOT NULL REFERENCES users(id),
    last_seq        BIGINT            NOT NULL DEFAULT 0,
    last_message_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT now(),

    CONSTRAINT pk_conversations         PRIMARY KEY (id),
    CONSTRAINT uq_conversations_dk      UNIQUE (direct_key),
    CONSTRAINT chk_conversations_direct CHECK (
        (type = 'DIRECT' AND direct_key IS NOT NULL AND title IS NULL) OR
        (type = 'GROUP'  AND direct_key IS NULL)
    )
);

-- ─────────────────────────────────────────────────────────────────────────────
-- conversation_participants
--
-- High-water mark design:
--   last_read_seq and last_delivered_seq are single integers per participant.
--   "Unread count" = conversations.last_seq - participants.last_read_seq.
--   This is O(1) storage and O(1) query.  Per-message read receipts require
--   O(participants × messages) storage and belong in a Phase 3+ push model.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE conversation_participants (
    conversation_id    UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id            UUID        NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    role               TEXT        NOT NULL DEFAULT 'MEMBER',
    joined_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at            TIMESTAMPTZ,
    last_delivered_seq BIGINT      NOT NULL DEFAULT 0,
    last_read_seq      BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_conv_participants PRIMARY KEY (conversation_id, user_id)
);

-- Supports: GET /api/conversations (all conversations for a user)
CREATE INDEX idx_conv_participants_user_id
    ON conversation_participants (user_id);
