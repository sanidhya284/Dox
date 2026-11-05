# ADR-0007 — Heartbeats, Idle Detection, and Delivered/Read Cursors

**Status:** Accepted  **Date:** 2026-11-05  **Phase:** 2d-lite

## Context
After 2c ([ADR-0006](0006-websocket-frame-protocol-v1.md)), two gaps remained:
1. **Dead sockets.** A dead connection (laptop asleep, NAT entry dropped) stayed in the registry until its 15-minute lifetime ended, so pushes to it went nowhere.
2. **Cursors.** Clients could not say "delivered" or "read", and unread counts included the user's own messages, because they were computed as `last_seq - last_read_seq`.

Presence and typing indicators are deliberately out of scope.

## Decision 1: protocol-level ping/pong, idle close 4006
- The server sends a WebSocket **ping** to every socket every `ws.heartbeat.interval` (default 25 s). Browsers and the Tomcat client answer with a **pong** automatically, so there is no application frame and no client code.
- A socket counts as alive if it sent a pong or any frame within `ws.heartbeat.timeout` (default 60 s). Otherwise it is closed with **`4006 idle timeout`** and unregistered; the client reconnects and recovers from history.
- Startup requires `timeout >= 2 × interval`, so a single lost pong never closes a healthy socket.
- Pings go through the same per-session decorator as every other send.
- `4006` (application range) rather than `1001`, so a client can tell "you were idle" from "server going away".
- Defaults: 25 s stays below common 30–60 s proxy idle timeouts. That is the usual reasoning, not a measured claim.

## Decision 2: cursors are per-participant, monotonic, guarded in the WHERE
The cursors are the existing `conversation_participants.last_delivered_seq` and `last_read_seq` columns (V4); no migration. One statement per update:
- read: `SET last_read_seq = :seq, last_delivered_seq = GREATEST(last_delivered_seq, :seq) WHERE conversation_id = :c AND user_id = :u AND left_at IS NULL AND last_read_seq < :seq`
- delivered: `SET last_delivered_seq = :seq WHERE ... AND last_delivered_seq < :seq`

**Why the guard is in the WHERE:**
- The row lock serialises concurrent updates, and each one re-checks the condition against the latest committed row. So an older cursor can never overwrite a newer one.
- A stale update matches no row and **writes nothing**: no new row version, no WAL. The row count (0 or 1) is the "advanced?" signal, which decides whether anything is pushed.
- Read implies delivered (`delivered >= read`).

**Order of checks:**
1. Membership. A non-member, a participant who has left, and an unknown conversation all get the same `CONVERSATION_NOT_FOUND`, even with an absurd seq.
2. Seq validation: negative, or greater than the conversation's `last_seq`, is a `VALIDATION_ERROR` (not clamped).
3. The update.

## Decision 3: unread excludes your own messages
`unread = count of messages with seq > my last_read_seq AND sender_id <> me`. It is computed with the `(conversation_id, seq)` index over the unread range; the list endpoint uses one grouped query.

Rejected: advancing the sender's read cursor when they send. That would silently mark earlier unread messages from others as read.

## Decision 4: cursor frames and REST fallback
- **Client → server:** `{"v":1,"type":"cursor","conversationId","kind":"delivered"|"read","seq"}`. There is no success reply. Errors come as an error frame with `clientMsgId: null`. This type is an additive change to protocol v1.
- **Server → client:** `{"v":1,"type":"cursor","conversationId","userId","lastDeliveredSeq","lastReadSeq"}`. It is pushed after commit, only when a cursor advanced, to the other participants' sockets and the user's own other sockets.
- **REST fallback:** `POST /api/conversations/{id}/cursor` `{"kind","seq"}` does the same and is pushed the same way.
- **Visibility:** `GET /api/conversations/{id}` returns each participant's `lastReadSeq` / `lastDeliveredSeq`; the list returns `myLastReadSeq`.

## Consequences
- **Cursor fan-out:** a cursor push goes to all participants. Clients should **debounce cursor frames**: send only the highest seq, at most about once per second. Large groups may need server-side coalescing later. There is no server-side cursor rate limit yet.
- **Read receipts are on for everyone;** there is no per-user privacy toggle.
- **"Delivered" is client-reported** (the client says it received the message), not server-observed.
- **Heartbeats are per instance** (single instance until Phase 4).
