-- V6: One-time WebSocket authentication tickets
-- Purpose: the client calls POST /api/ws/ticket (with its normal Bearer JWT)
-- and receives a short-lived, single-use random ticket.  The WebSocket
-- handshake at /ws?ticket=... consumes the ticket atomically with
-- DELETE … RETURNING, binding the connection to that user.  Only the
-- SHA-256 hash is stored; the raw ticket is never persisted.

CREATE TABLE ws_tickets (
    ticket_hash TEXT         NOT NULL PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ws_tickets_expires_at ON ws_tickets (expires_at);
