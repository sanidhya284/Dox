# ADR-0005 — Connection Registry and After-Commit Socket Closing

**Status:** Accepted  **Date:** 2026-11-04  **Phase:** 2b-ii

## Context
A WebSocket authenticates once, at the handshake ([ADR-0004](0004-websocket-authentication-tickets.md)), so it can outlive the 15-minute access token that minted its ticket. The socket-lifetime decision (A + B theft only, recorded in [architecture.md](../architecture.md), "WebSocket Socket Lifetime") needs the server to know each user's open sockets: to close each one after about 15 minutes, and to close all of a user's sockets when a refresh-token replay revokes their token family ([ADR-0003](0003-authentication-strategy.md)). Phase 2c will need the same map to push messages.

## Decision 1: an in-memory registry, userId → sessions
`ConnectionRegistry` keeps `ConcurrentHashMap<UUID, Set<WebSocketSession>>`. One user can have several sockets (tabs, devices). `register` and `unregister` each run inside `ConcurrentHashMap.compute`, which is atomic per key. `unregister` returns `null` when the set empties, so the key is removed in the same atomic step.

Why `compute` for both: a two-step version (`computeIfAbsent(...).add(s)`, then a separate "remove the key if the set is empty") can remove the key just after another thread added a socket to that same set, which loses the socket. `ConnectionRegistryTest` (4 threads × 200,000 register/unregister cycles for one user) caught that version in 6 of 6 runs (13 to 28 lost registers per run) and passes with `compute`.

The registry exposes session ids, not raw sessions: sending to one session from several threads is unsafe, and 2c will wrap sessions before sending (see HANDOFF, 2c).

Rejected: storing sockets in PostgreSQL. A socket is process-local and cannot be closed or written to from another process, so a table would only mirror memory. PostgreSQL stays the source of truth for messages, not for live connections.

Consequence: single instance only. Phase 4 needs a cross-instance fan-out (for example Redis pub/sub) to reach sockets held by other instances.

## Decision 2: socket lifetime
Each socket is closed with code `4001` about 15 minutes after its handshake (`ws.session.max-lifetime`); the client reconnects with a fresh ticket, which needs a valid access token. No jitter yet (possible reconnect bursts are a Phase 4 concern).

## Decision 3: revocation closes sockets via an after-commit event
`auth` publishes `RefreshTokenFamilyRevokedEvent(userId, familyId)` when a replayed refresh token revokes a family. `realtime` handles it with `@TransactionalEventListener(phase = AFTER_COMMIT)` and closes all of that user's sockets with `4002`. The close itself runs on a realtime executor, not the request thread.

- **Module boundary:** auth knows nothing about realtime; realtime depends only on auth's event type, not on its services or repositories.
- **Why after commit:** the revocation commits even though the request returns 401 (`noRollbackFor = ApiException.class`). Closing only after commit means sockets are never closed for a revocation that did not persist. The same rule (side effects after commit) will govern pushes in 2c.
- **Why off the request thread:** closing a socket sends a close frame, which can wait on a slow peer; the public `/api/auth/refresh` must not wait on that.
- **Not on logout:** the access token carries no session id, so the server cannot tell which sockets belong to the device that logged out (known limitation).

Implementation progress is tracked in [HANDOFF.md](../HANDOFF.md), not here.
