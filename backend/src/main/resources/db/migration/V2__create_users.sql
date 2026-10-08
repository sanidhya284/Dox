-- V2__create_users.sql
-- ─────────────────────────────────────────────────────────────────────────────
-- Creates the users table.
--
-- Case-insensitive uniqueness for username and email is handled via the
-- citext extension (character-insensitive text type).  This enforces the
-- invariant at the PostgreSQL type level — not by application convention —
-- so a missed .toLowerCase() in Java cannot create a duplicate account.
--
-- Why citext over LOWER() functional indexes?
--   A functional index requires every query to use LOWER(column) = LOWER(?)
--   to hit the index.  Any query that omits LOWER() bypasses the constraint
--   silently.  citext makes all comparisons case-insensitive automatically.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    username      CITEXT      NOT NULL,
    email         CITEXT      NOT NULL,
    password_hash TEXT        NOT NULL,
    display_name  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_users             PRIMARY KEY (id),
    CONSTRAINT uq_users_username    UNIQUE (username),
    CONSTRAINT uq_users_email       UNIQUE (email)
);
