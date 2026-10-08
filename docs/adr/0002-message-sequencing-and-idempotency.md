# ADR-0002 — Message Sequencing, Idempotency, and Pagination Strategy

**Status:** Accepted
**Date:** 2026-10-08
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
- No additional round-trip compared to a `SELECT … FOR UPDATE` + separate `UPDATE`.
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
- If `RETURNING *` returns nothing: a concurrent duplicate beat us — fetch and return the existing row as 200.

Two concurrent requests with the same `client_msg_id` cannot both insert. The database constraint is the single source of truth. Application-level checks (e.g., a `SELECT` before `INSERT`) are a performance fast-path, not a correctness guarantee — two simultaneous requests both pass the `SELECT` check, then one wins the INSERT and one hits the constraint.

**Security note:** `client_msg_id` is scoped by `sender_id`. User A cannot affect User B's idempotency keys.

---

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

- Sequence numbers are gapless within a single sending transaction. If a transaction is rolled back (e.g., the `INSERT INTO messages` fails after the sequence is allocated), a gap appears. This is acceptable — gaps are observable but do not violate correctness. Clients must not assume sequence numbers are contiguous.
- The `client_msg_id` uniqueness scope is `(sender_id, client_msg_id)`. A client must not reuse a UUID for a different message (even to a different conversation). A UUID generated per send is always safe.
