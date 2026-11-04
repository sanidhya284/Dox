# ADR-0004 — WebSocket Authentication: One-Time Tickets in PostgreSQL

**Status:** Accepted  **Date:** 2026-10-23  **Phase:** 2
**Amended:** 2026-11-04. (1) Merged the extra rationale from a duplicate draft of this ADR (`0004-webSockets.md`, deleted): the Redis and in-memory reasoning, the named mutation-tested tests, the cleanup gap and the revisit criterion. Two claims in that draft were wrong and were not carried over: there is no `TicketService` interface (`WsTicketService` is a concrete class), and benchmarking is Phase 5, not Phase 8. (2) Corrected the socket-closing consequence to match ADR-0005. The decision itself is unchanged.

## Context
Browsers cannot set an Authorization header on a WebSocket handshake. Putting the JWT in the URL leaks it into logs and history; cookies are sent automatically and enable cross-site WebSocket hijacking unless Origin is validated.

## Decision
The client calls `POST /api/ws/ticket` with its normal Bearer JWT and receives a random 32-byte ticket (base64url, valid 30 seconds). It opens `/ws?ticket=...`; the handshake consumes the ticket and binds the connection to that user. Tickets are stored only as SHA-256 hashes and consumed with a single `DELETE ... RETURNING`, so a ticket works at most once even under concurrent use (verified by a 20-thread test and a mutation check). Origin is validated on the handshake regardless, before the ticket is consumed: it must exactly match an entry in `ws.allowed-origins`, which has no default and refuses startup if empty, blank or wildcarded. Spring's own origin check runs on the same list as a second layer. Only the exact path `/ws` is exempt from JWT auth.

## Why PostgreSQL
It keeps PostgreSQL the source of truth, needs no new infrastructure, and works unchanged when Phase 4 runs several instances (a ticket issued by one instance can be redeemed on another). Cost: one small write and one delete per connection attempt, off the message path. `DELETE ... RETURNING` is the same one-statement atomic claim already used for refresh-token rotation.

- **In-memory map, rejected:** a ticket minted on one instance is invisible to another. Multi-instance is a planned stage (Phase 4), so this would be built knowing it needs a rewrite.
- **Redis, rejected:** Redis is not in the stack until Phase 4. More importantly, everywhere it will be used here (pub/sub routing, presence) it is allowed to fail soft. Authentication is not: if tickets lived in Redis, a Redis outage would stop every new WebSocket connection on every instance. PostgreSQL is already the one hard dependency, so storing tickets there adds no new failure mode.

## Consequences
- A leaked ticket in a log is useless after use or after 30 seconds.
- Authentication happens only at the handshake, so a connection can outlive the 15-minute access token. Handled in 2b-ii ([ADR-0005](0005-connection-registry-and-after-commit-socket-closing.md)): every socket is closed after `ws.session.max-lifetime` (4001), and a refresh-token replay closes all of that user's sockets (4002). Logout does not close sockets (known limitation).
- Expired rows are deleted opportunistically when a ticket is issued (that user's rows). There is no global sweep yet; this is a storage gap, not a correctness issue (expired tickets are never accepted).
- A user can mint many tickets; per-user rate limiting is not implemented yet.
- The one-time property is under test: replacing `DELETE ... RETURNING` with a plain `SELECT` fails `issuedTicketConsumesToTheRightUserExactlyOnce`, `concurrentConsumeOfOneTicketYieldsExactlyOneSuccess` and `twoTicketsForOneUserAreIndependent` in `WsTicketTest`.

## Revisit when
Connection-establishment rate (not message throughput) is measured (Phase 5) and the ticket round-trips are shown to be the limiting factor.
