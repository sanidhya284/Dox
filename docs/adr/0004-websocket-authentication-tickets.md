# ADR-0004 — WebSocket Authentication: One-Time Tickets in PostgreSQL

**Status:** Accepted  **Date:** 2026-10-23  **Phase:** 2

## Context
Browsers cannot set an Authorization header on a WebSocket handshake. Putting the JWT in the URL leaks it into logs and history; cookies are sent automatically and enable cross-site WebSocket hijacking unless Origin is validated.

## Decision
The client calls `POST /api/ws/ticket` with its normal Bearer JWT and receives a random 32-byte ticket (base64url, valid 30 seconds). It opens `/ws?ticket=...`; the handshake consumes the ticket and binds the connection to that user. Tickets are stored only as SHA-256 hashes and consumed with a single `DELETE ... RETURNING`, so a ticket works at most once even under concurrent use (verified by a 20-thread test and a mutation check). Origin is validated on the handshake regardless, before the ticket is consumed: it must exactly match an entry in `ws.allowed-origins`, which has no default and refuses startup if empty, blank or wildcarded. Spring's own origin check runs on the same list as a second layer. Only the exact path `/ws` is exempt from JWT auth.

## Why PostgreSQL
It keeps PostgreSQL the source of truth, needs no new infrastructure, and works unchanged when Phase 4 runs several instances (a ticket issued by one instance can be redeemed on another). Cost: one small write and one delete per connection, and expired rows must be cleaned up (done opportunistically on issue). Redis was rejected because it is not in the stack until Phase 4 and its outage would block new connections.

## Consequences
- A leaked ticket in a log is useless after use or after 30 seconds.
- Authentication happens only at the handshake; the connection can outlive the 15-minute access token. Logout and refresh-family revocation must close that user's sockets (Phase 2b).
- A user can mint many tickets; per-user rate limiting is not implemented yet.
