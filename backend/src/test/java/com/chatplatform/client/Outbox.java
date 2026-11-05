package com.chatplatform.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The reference client's outbox (ADR-0008). A send intent gets its clientMsgId ONCE, at enqueue time,
 * and keeps it for every retry, so the server's idempotency turns any number of retries into exactly
 * one message. Bounded: when full, enqueue is refused (back-pressure to the caller). FIFO. An entry
 * leaves only on an ack (returned with its body, so the client can record its own message) or a
 * terminal error. Thread-safe (the client's socket callbacks and its timer both touch it).
 */
public final class Outbox {

    public record Entry(UUID clientMsgId, UUID conversationId, String body, int attempts, long lastSentNanos) {
        boolean neverSent() {
            return attempts == 0;
        }
    }

    private final int capacity;
    private final Map<UUID, Entry> pending = new LinkedHashMap<>();

    public Outbox(int capacity) {
        this.capacity = capacity;
    }

    public synchronized Optional<Entry> enqueue(UUID conversationId, String body) {
        if (pending.size() >= capacity) {
            return Optional.empty();
        }
        Entry e = new Entry(UUID.randomUUID(), conversationId, body, 0, 0L);
        pending.put(e.clientMsgId(), e);
        return Optional.of(e);
    }

    /** Records a (re)send of the SAME clientMsgId. */
    public synchronized void markSent(UUID clientMsgId, long nowNanos) {
        pending.computeIfPresent(clientMsgId,
                (id, e) -> new Entry(id, e.conversationId(), e.body(), e.attempts() + 1, nowNanos));
    }

    /** Never sent, or sent and not acked within {@code ackTimeoutNanos}: resend with the same id. */
    public synchronized List<Entry> dueForRetry(long nowNanos, long ackTimeoutNanos) {
        List<Entry> due = new ArrayList<>();
        for (Entry e : pending.values()) {
            if (e.neverSent() || nowNanos - e.lastSentNanos() > ackTimeoutNanos) {
                due.add(e);
            }
        }
        return due;
    }

    public synchronized Optional<Entry> acked(UUID clientMsgId) {
        return Optional.ofNullable(pending.remove(clientMsgId));
    }

    public synchronized Optional<Entry> failed(UUID clientMsgId) {
        return Optional.ofNullable(pending.remove(clientMsgId));
    }

    public synchronized Optional<Entry> get(UUID clientMsgId) {
        return Optional.ofNullable(pending.get(clientMsgId));
    }

    public synchronized List<Entry> pending() {
        return List.copyOf(pending.values());
    }

    public synchronized int size() {
        return pending.size();
    }
}
