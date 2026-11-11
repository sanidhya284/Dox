/**
 * Bounded outbox with a STABLE clientMsgId per send (ADR-0008), ported from the Java reference client.
 * Every retry of one send reuses its id, so the server's idempotency makes a retry harmless.
 */
export interface OutboxEntry {
  readonly clientMsgId: string;
  readonly conversationId: string;
  readonly body: string;
  readonly attempts: number;
  /** ms timestamp of the last send; -Infinity means due now. */
  readonly lastSentAt: number;
  /** Not sent again before this ms timestamp (a RATE_LIMITED refusal's retryAfterMs; ADR-0012). */
  readonly notBefore?: number;
}

export class Outbox {
  private readonly entries = new Map<string, OutboxEntry>();

  constructor(
    private readonly capacity: number,
    private readonly newId: () => string = () => crypto.randomUUID(),
  ) {}

  /** null when full: the caller tells the user instead of growing without bound. */
  enqueue(conversationId: string, body: string): OutboxEntry | null {
    if (this.entries.size >= this.capacity) {
      return null;
    }
    const entry: OutboxEntry = { clientMsgId: this.newId(), conversationId, body, attempts: 0, lastSentAt: -Infinity };
    this.entries.set(entry.clientMsgId, entry);
    return entry;
  }

  markSent(clientMsgId: string, now: number): void {
    const e = this.entries.get(clientMsgId);
    if (e) {
      this.entries.set(clientMsgId, { ...e, attempts: e.attempts + 1, lastSentAt: now });
    }
  }

  /** Due again on the next flush (retryable error, or a new socket). */
  markDue(clientMsgId: string): void {
    const e = this.entries.get(clientMsgId);
    if (e) {
      this.entries.set(clientMsgId, { ...e, lastSentAt: -Infinity });
    }
  }

  /** Due again at `at`, not before (the server said when: RATE_LIMITED's retryAfterMs). Same clientMsgId. */
  deferUntil(clientMsgId: string, at: number): void {
    const e = this.entries.get(clientMsgId);
    if (e) {
      this.entries.set(clientMsgId, { ...e, lastSentAt: -Infinity, notBefore: at });
    }
  }

  /** A new socket: everything unacked is due now, except what the server asked us to hold back. */
  markAllDue(): void {
    for (const id of [...this.entries.keys()]) {
      this.markDue(id);
    }
  }

  /** Never sent, or sent longer than ackTimeoutMs ago without an ack; and not held back (notBefore). */
  dueForRetry(now: number, ackTimeoutMs: number): OutboxEntry[] {
    return [...this.entries.values()]
      .filter((e) => now - e.lastSentAt > ackTimeoutMs && now >= (e.notBefore ?? -Infinity));
  }

  /** Removes and returns the entry (ack or terminal error). */
  remove(clientMsgId: string): OutboxEntry | undefined {
    const e = this.entries.get(clientMsgId);
    this.entries.delete(clientMsgId);
    return e;
  }

  pending(): OutboxEntry[] {
    return [...this.entries.values()];
  }

  size(): number {
    return this.entries.size;
  }
}
