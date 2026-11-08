package com.chatplatform.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The application's own meters (ADR-0009, Decision 1). Every tag value comes from a fixed enum here,
 * never from a user, conversation or session id, so the number of time series is bounded. All
 * counters are registered up front, so a scrape shows them at 0 before the first event.
 */
@Component
public class ChatMetrics {

    public enum Channel { REST, WS }

    public enum SendOutcome { CREATED, DUPLICATE, REJECTED }

    public enum TicketOutcome { ISSUED, CONSUMED, REJECTED }

    public enum CursorOutcome { ADVANCED, STALE }

    /** Close codes counted individually; anything else is "other". */
    static final Set<Integer> NAMED_CLOSE_CODES = Set.of(1000, 1001, 1007, 1009, 4001, 4002, 4003, 4005, 4006, 4007);

    private final Map<String, Counter> sent;
    private final Map<TicketOutcome, Counter> tickets;
    private final Map<CursorOutcome, Counter> cursors;
    private final Map<String, Counter> closes;
    private final Counter pushDropped;
    private final Counter headsRequests;
    private final Timer sendAck;

    public ChatMetrics(MeterRegistry registry) {
        sent = new java.util.HashMap<>();
        for (Channel c : Channel.values()) {
            for (SendOutcome o : SendOutcome.values()) {
                sent.put(c + "/" + o, Counter.builder("chat.messages.sent")
                        .description("Message sends by channel and outcome")
                        .tag("channel", lower(c)).tag("outcome", lower(o)).register(registry));
            }
        }
        tickets = counters(TicketOutcome.values(), o -> Counter.builder("chat.ws.tickets")
                .description("WebSocket tickets issued, consumed by a handshake, or rejected")
                .tag("outcome", lower(o)).register(registry));
        cursors = counters(CursorOutcome.values(), o -> Counter.builder("chat.cursor.updates")
                .description("Cursor updates that advanced a cursor or were stale (moved nothing)")
                .tag("outcome", lower(o)).register(registry));
        closes = new java.util.HashMap<>();
        for (int code : NAMED_CLOSE_CODES) {
            closes.put(String.valueOf(code), closeCounter(registry, String.valueOf(code)));
        }
        closes.put("other", closeCounter(registry, "other"));
        pushDropped = Counter.builder("chat.push.dropped")
                .description("Pushes dropped because the push queue was full (clients recover from history)")
                .tag("reason", "queue_full").register(registry);
        headsRequests = Counter.builder("chat.heads.requests")
                .description("Anti-entropy heads requests").register(registry);
        sendAck = Timer.builder("chat.ws.send.ack")
                .description("Server side of a WebSocket send: frame received to ack handed to the socket")
                .publishPercentileHistogram().register(registry);
    }

    private static Counter closeCounter(MeterRegistry registry, String code) {
        return Counter.builder("chat.ws.closes").description("WebSocket closes by close code")
                .tag("code", code).register(registry);
    }

    private static <E extends Enum<E>> Map<E, Counter> counters(E[] values, Function<E, Counter> make) {
        return java.util.Arrays.stream(values).collect(Collectors.toUnmodifiableMap(v -> v, make));
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Counts a send. CREATED and DUPLICATE are counted only after the transaction commits, so a send
     * that rolls back is never counted as created; outside a transaction it is counted at once.
     */
    public void messageSent(Channel channel, SendOutcome outcome) {
        Counter c = sent.get(channel + "/" + outcome);
        if (outcome == SendOutcome.REJECTED) {
            c.increment();
        } else {
            afterCommit(c::increment);
        }
    }

    public void ticket(TicketOutcome outcome) {
        tickets.get(outcome).increment();
    }

    /** Counted after commit, like sends. */
    public void cursorUpdate(CursorOutcome outcome) {
        afterCommit(cursors.get(outcome)::increment);
    }

    public void wsClosed(int code) {
        closes.getOrDefault(String.valueOf(code), closes.get("other")).increment();
    }

    public void pushDropped() {
        pushDropped.increment();
    }

    public void headsRequest() {
        headsRequests.increment();
    }

    public Timer sendAckTimer() {
        return sendAck;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
