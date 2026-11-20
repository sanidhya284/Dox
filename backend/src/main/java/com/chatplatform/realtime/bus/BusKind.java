package com.chatplatform.realtime.bus;

/** What a bus envelope carries (ADR-0013). Lower case: it is also the {@code kind} metric tag value. */
@SuppressWarnings("java:S115") // lower-case constants on purpose: they are wire and metric values
public enum BusKind {
    message, cursor, close_user
}
