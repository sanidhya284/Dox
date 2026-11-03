# ADR-0002 — Message Sequencing, Idempotency, and Pagination Strategy

**Status:** Accepted
**Date:** 2026-10-08 (amended 2026-10-17)
**Phase:** 1 — REST Core

---

## Context

A messaging system needs three correctness properties for message delivery:

1. **Total order** — every message in a conversation must have an unambiguous position in the sequence. Two messages must never occupy the same position.
2. **Idempotency** — if a client retries a failed send (network timeout, server restart), the message must appear exactly once, not twice.
3. **Efficient history retrieval** — fetching past messages must remain fast as conversations grow to millions of messages.

---

## Decision 1 — Sequence Allocation: `UPDATE … RETURNING` instead of `MAX(seq) + 1`

### Why `MAX(seq) + 1` is incorrect under concurrency

```
T1: SELECT MAX(seq) = 5
T2: SELECT MAX(seq) = 5     ← T1 hasn't committed yet
T1: INSERT (seq = 6)
T2: INSERT (seq = 6)        ← UNIQUE VIOLATION
```

Under PostgreSQL's default `READ COMMITTED` isolation, T2's `SELECT MAX` does not see T1's uncommitted INSERT. Both transactions read the same maximum, both attempt to insert the same sequence number, and one fails.

The retry logic is complex: the failing transaction must catch the violation, re-read `MAX(seq)`, and retry the INSERT — which itself can race again.

### Chosen approach

```sql
UPDATE conversations
SET    last_seq = last_seq + 1,
       last_message_at = now()
WHERE  id = :conversationId
RETURNING last_seq
```

A PostgreSQL `UPDATE` on a row acquires a **row-level exclusive lock** before modifying it. Two concurrent transactions attempting to update the same conversation row **serialize automatically** — one waits until the other commits or rolls back. No application-level synchronization (`synchronized`, Redis locks) is required.

**Properties:**
- Allocation and message timestamp update happen atomically in one statement.
- The lock is taken one statement earlier (`SELECT ... FOR UPDATE`, see Decision 2a) so the idempotency check runs under it; the `UPDATE` still performs the allocation.
- Correct at `READ COMMITTED` (the default). Escalating to `SERIALIZABLE` would add unnecessary abort risk.

---

## Decision 2 — Idempotency: Client-generated `client_msg_id` + DB UNIQUE constraint

### Why the database constraint is the correctness mechanism

The client generates a UUID (`client_msg_id`) before sending. If the request fails mid-flight and the client retries with the same UUID:

```sql
INSERT INTO messages (…, client_msg_id, …)
VALUES (…)
ON CONFLICT (sender_id, client_msg_id) DO NOTHING
RETURNING *
```

- If `RETURNING *` returns a row: the insert succeeded — return it as 201.
- If `RETURNING *` returns nothing, the key already exists somewhere the pre-check did not catch (for example the same key racing in a different conversation). The request fails with `409 IDEMPOTENCY_KEY_CONFLICT`; it never returns another conversation's message.

Two concurrent requests with the same `client_msg_id` cannot both insert. Within one conversation the row lock makes the pre-insert lookup authoritative; the UNIQUE constraint remains the backstop for the cross-conversation case. See Decision 2a.

**Security note:** `client_msg_id` is scoped by `sender_id`. User A cannot affect User B's idempotency keys.

---

## Decision 2a — Send Order and Key Reuse (amended 2026-10-17)

The first implementation did: membership check, lookup by `(sender_id, client_msg_id)`, increment `last_seq`, then `INSERT ... ON CONFLICT DO NOTHING`. Integration tests exposed two defects:

1. **Burned sequence numbers.** Concurrent duplicates both passed the lookup and both incremented `last_seq`. The loser's insert returned nothing, but the transaction still committed, leaving a sequence number with no message (a test saw seq 5 where 2 was expected). Phase 3 gap detection would have read that as a lost message.
2. **Wrong message returned.** The lookup ignored conversation and body, so reusing a key in another conversation silently returned the message from the first one.

The fix, inside one transaction:

1. Check membership (non-members get 404).
2. `SELECT id FROM conversations WHERE id = :id FOR UPDATE`.
3. Look up `(sender_id, client_msg_id)`. If found with the same conversation and body, return the original message. If found with a different conversation or body, return `409 IDEMPOTENCY_KEY_CONFLICT`.
4. Only then increment `last_seq` and insert. If the insert still hits the unique constraint, return 409.

Duplicates in one conversation queue on the row lock and never reach the increment, so no sequence number is burned. Covered by `duplicateConcurrentSendsLeaveNoSequenceGap`, the two 409 tests, and a 100-sender contiguity assertion.

## Decision 3 — Pagination: Keyset (`WHERE seq < :cursor`) instead of OFFSET

### Why OFFSET pagination degrades with conversation size

```sql
-- OFFSET approach (do not use)
SELECT * FROM messages ORDER BY seq DESC LIMIT 50 OFFSET 50000
```

PostgreSQL must scan and discard 50,000 rows to return page 1001 of a conversation. Cost grows linearly with the conversation length.

### Chosen approach

```sql
SELECT * FROM messages
WHERE  conversation_id = :id AND seq < :cursor
ORDER  BY seq DESC
LIMIT  :limit
```

The index `ON messages (conversation_id, seq DESC)` means PostgreSQL performs a single B-tree seek to the cursor position and reads the next N rows. Cost is O(LIMIT), not O(conversation_size). A conversation with 10 million messages has the same page-load cost as one with 100 messages.

**Cursor stability:** New messages arriving between page fetches do not shift older rows. A LIMIT/OFFSET approach would show duplicates or gaps when new messages arrive.

---

## Consequences

- Sequence numbers within a conversation are contiguous: the increment and the insert commit or roll back together, and duplicate sends never reach the increment. Clients can still treat a gap in received sequence numbers as a sign of a missed message.
- The `client_msg_id` uniqueness scope is `(sender_id, client_msg_id)`. Reusing a key for a different conversation or body is rejected with 409, so a client must generate a fresh UUID per logical message and reuse it only when retrying that same message.
