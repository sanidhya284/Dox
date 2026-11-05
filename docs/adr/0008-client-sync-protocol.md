# ADR-0008 — Client Sync Protocol: Reliable Delivery and Recovery

**Status:** Accepted  **Date:** 2026-11-05  **Phase:** 3

## Context
Pushes are best effort by design ([ADR-0006](0006-websocket-frame-protocol-v1.md), [ADR-0007](0007-heartbeats-and-read-cursors.md)). They can be dropped: a full push queue, a `4003` slow consumer, a closed socket, an idle `4006`. They can arrive out of `seq` order, and a duplicate send is acked but never re-pushed. PostgreSQL is the source of truth. Phase 3 defines how a client **converges** on it, and proves it with a reference client (test sources, `client/ReferenceClient`) under seeded faults.

## Decision 1: server APIs for catching up
- `GET /api/conversations/{id}/messages?after={seq}&limit={n}`: strictly later messages, oldest first, with an exact `hasMore` (fetch `limit + 1`). It is served by the `(conversation_id, seq)` index in index order.
- `GET /api/conversations/heads`: `[{conversationId, lastSeq}]` for my active conversations, as one indexed join.
- `lastSeq` appears in the conversation list and the single view.
- **No batch catch-up endpoint.** A reconnecting client reads `heads` once, then pages forward only where it is behind. A batch call would only save round-trips for clients behind in many conversations at once; reconsider if Phase 5 measurements show that matters.

## Decision 2: the client algorithm
1. **Outbox.**
   - A send gets a `clientMsgId` **once**, at enqueue time, and keeps it for every retry. The server's idempotency (ADR-0002) turns any number of retries into one row.
   - The outbox is bounded (enqueue is refused when full).
   - An entry is resent when no ack arrives within the **ack timeout**, on the **same socket**, and again after every reconnect.
   - The ack also records the client's own message locally (the body comes from the outbox entry), so `contiguousSeq` advances without waiting for a push. A sender's own socket never gets a push for its message.
2. **Errors.**
   - Terminal, so the entry is dropped and reported: `VALIDATION_ERROR`, `CONVERSATION_NOT_FOUND`, `IDEMPOTENCY_KEY_CONFLICT`.
   - Retryable: `INTERNAL_ERROR` (for example a failed commit), an ack timeout, and any unknown code.
3. **Reconnect.** A fresh ticket and **exponential backoff with full jitter**: `delay = random[0, min(cap, base × 2^attempt))` (base 100 ms, cap 10 s), reset after a successful connect. Full jitter spreads clients that were dropped together.

   | Close | Action |
   |---|---|
   | `4001`, `4003`, `4006`, `1001`, `1006` | Reconnect |
   | `4002` | Stop; re-login required |
   | `1007`, `4004`, `4005` | Stop: client bug, never loop |
   | `1008` | Stop: server bug |
   | `4007` | Stop: too many open sockets for this user (added 2026-11-06, [ADR-0006](0006-websocket-frame-protocol-v1.md) Decision 2a); the user closes a tab or device and reconnects by hand |

4. **Subscribe first, then fetch.** Open the socket first, so new commits arrive as pushes; then page `?after=contiguousSeq` for each conversation where `heads` says the client is behind. Fetching first and subscribing afterwards loses anything committed in between.
5. **Local history.** Deduplicate by message id, order by `seq`, and track `contiguousSeq`, the highest seq with every earlier seq present.
6. **Gap detection.** A push with `seq > contiguousSeq + 1` triggers a fetch from `contiguousSeq`; the client never guesses.
7. **Anti-entropy.** Every `30 s × random(0.5..1.5)`, compare `heads` with `contiguousSeq` and fetch where behind. Gap detection cannot notice a dropped **last** push, because no later push ever arrives; anti-entropy can.
8. **Token expiry.** On a 401 from a REST call, refresh **once (single flight)** and retry. Concurrent callers wait for that one refresh: a second refresh would reuse the rotated refresh token and, by ADR-0003, revoke the whole family. Each client has its own login and token family.

## Decision 3: proof, not claims
- `ReferenceClientTest` holds deterministic targeted tests, each with a positive form and a negative form (a bug flag makes it fail): dedupe, subscribe-first, gap fetch, anti-entropy for a dropped last push, same-id resend versus a new id, no loop on protocol errors, close-code handling, single-flight refresh.
- `ConvergenceTest`: seeded faults (dropped connections, pushes and acks, duplicated and delayed sends, failed commits, server closes, and a tail whose pushes are all dropped). With no forced sync, every client must equal PostgreSQL. CI runs 3 seeds.
- This is evidence of **correctness on one instance with a bounded workload**, not of throughput or scale.

## Consequences and limitations
- **The reference client lives in test sources;** it documents and verifies the algorithm and is not a published SDK.
- **Anti-entropy polling cost** is one `heads` query per client per interval, about O(conversations) rows each time. It is **not measured**: that is a Phase 5 item, to decide whether 30 s is right and whether `heads` needs caching. `heads` was chosen over polling the full list because the list runs extra per-conversation queries.
- **`heads` is unpaginated:** it returns every active conversation in one response; users with very many conversations will need pagination or a "changed since" variant later.
- **Seeds reproduce the faults, not the thread schedule.**
- **Masked bugs:** in the convergence test, a missing gap detection or a fetch-before-subscribe bug is repaired by the other layers (anti-entropy, later pushes); the targeted tests carry those guarantees.
- **No server-sent "resync" hint;** clients rely on gap detection and anti-entropy.
- **Long offline periods:** a client offline longer than the refresh-token lifetime must log in again.
- **Single instance;** multi-instance delivery is Phase 4.
