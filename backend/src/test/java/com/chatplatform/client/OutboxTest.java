package com.chatplatform.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The reference client's outbox: bounded, FIFO, one clientMsgId per intent, ack timeouts. */
class OutboxTest {

    private static final long TIMEOUT = Duration.ofSeconds(2).toNanos();
    private final UUID conv = UUID.randomUUID();

    @Test
    void clientMsgIdIsAssignedOnceAndKeptAcrossRetries() {
        Outbox o = new Outbox(10);
        Outbox.Entry e = o.enqueue(conv, "hello").orElseThrow();
        o.markSent(e.clientMsgId(), 0);

        Outbox.Entry retry = o.dueForRetry(TIMEOUT + 1, TIMEOUT).getFirst();
        assertThat(retry.clientMsgId()).isEqualTo(e.clientMsgId());
        assertThat(o.pending()).extracting(Outbox.Entry::clientMsgId).containsExactly(e.clientMsgId());
    }

    @Test
    void isBoundedAndRefusesWhenFull() {
        Outbox o = new Outbox(3);
        assertThat(o.enqueue(conv, "a")).isPresent();
        assertThat(o.enqueue(conv, "b")).isPresent();
        assertThat(o.enqueue(conv, "c")).isPresent();
        assertThat(o.enqueue(conv, "d")).as("full: back-pressure to the caller").isEmpty();
        assertThat(o.size()).isEqualTo(3);
    }

    @Test
    void ackRemovesTheEntryAndReturnsItsBodyOnce() {
        Outbox o = new Outbox(10);
        Outbox.Entry e = o.enqueue(conv, "mine").orElseThrow();

        assertThat(o.acked(e.clientMsgId())).get().extracting(Outbox.Entry::body).isEqualTo("mine");
        assertThat(o.acked(e.clientMsgId())).as("duplicate ack is harmless").isEmpty();
        assertThat(o.size()).isZero();
    }

    @Test
    void entryIsDueOnlyAfterTheAckTimeoutSinceItWasLastSent() {
        Outbox o = new Outbox(10);
        Outbox.Entry e = o.enqueue(conv, "x").orElseThrow();
        assertThat(o.dueForRetry(0, TIMEOUT)).as("never sent: due now").hasSize(1);

        o.markSent(e.clientMsgId(), 1_000);
        assertThat(o.dueForRetry(1_000 + TIMEOUT - 1, TIMEOUT)).isEmpty();
        assertThat(o.dueForRetry(1_000 + TIMEOUT + 1, TIMEOUT)).hasSize(1);

        o.markSent(e.clientMsgId(), 1_000 + TIMEOUT + 1);
        assertThat(o.dueForRetry(1_000 + TIMEOUT + 2, TIMEOUT)).as("just resent").isEmpty();
        assertThat(o.pending().getFirst().attempts()).isEqualTo(2);
    }

    @Test
    void terminalFailureRemovesTheEntryAndOrderIsFifo() {
        Outbox o = new Outbox(10);
        Outbox.Entry a = o.enqueue(conv, "a").orElseThrow();
        Outbox.Entry b = o.enqueue(conv, "b").orElseThrow();
        Outbox.Entry c = o.enqueue(conv, "c").orElseThrow();

        assertThat(o.failed(b.clientMsgId())).isPresent();
        assertThat(o.pending()).extracting(Outbox.Entry::body).containsExactly("a", "c");
        assertThat(a.clientMsgId()).isNotEqualTo(c.clientMsgId());
    }

    @Test
    void errorClassesSplitRetryableFromTerminal() {
        assertThat(ErrorClass.of("INTERNAL_ERROR")).isEqualTo(ErrorClass.RETRYABLE);
        assertThat(ErrorClass.of("VALIDATION_ERROR")).isEqualTo(ErrorClass.TERMINAL);
        assertThat(ErrorClass.of("CONVERSATION_NOT_FOUND")).isEqualTo(ErrorClass.TERMINAL);
        assertThat(ErrorClass.of("IDEMPOTENCY_KEY_CONFLICT")).isEqualTo(ErrorClass.TERMINAL);
        assertThat(ErrorClass.of("SOMETHING_NEW")).as("unknown codes are retried").isEqualTo(ErrorClass.RETRYABLE);
    }
}
