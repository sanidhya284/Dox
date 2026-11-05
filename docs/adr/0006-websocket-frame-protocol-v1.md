# ADR-0006 — WebSocket Frame Protocol v1: Send, Ack, Push

**Status:** Accepted  **Date:** 2026-11-04  **Phase:** 2c

## Context
After 2b-ii the `/ws` socket is authenticated and tracked ([ADR-0005](0005-connection-registry-and-after-commit-socket-closing.md)) but carries no messages. Phase 2c adds sending and real-time delivery. PostgreSQL stays the source of truth: an ack must mean "durably committed", and a missed push must never mean a missed message, because clients can always page `GET /api/conversations/{id}/messages`.

## Decision 1: a small versioned JSON envelope
Every frame is a JSON text frame `{"v":1,"type":"...", ...}`. Version 1 has:

| Direction | type | Fields |
|---|---|---|
| client → server | `send` | `conversationId`, `clientMsgId`, `body` |
| server → client | `ack` | `clientMsgId`, `messageId`, `conversationId`, `seq`, `createdAt` |
| server → client | `message` | `message`: `{id, conversationId, seq, senderId, clientMsgId, body, createdAt}` |
| server → client | `error` | `clientMsgId` (null if the client sent none), `code`, `message` |

`v` lets the protocol evolve (Phase 3 sync cursors) without guessing. Unknown extra fields are ignored. The user is always the one authenticated at the handshake, never a field in the frame.

## Decision 2: error frame vs close
- **Close the socket** when the frame cannot be understood well enough to reply to:

  | Case | Close code |
  |---|---|
  | Not JSON or not an object; `v` or `type` missing or of the wrong JSON type; `clientMsgId` present but not a UUID string | `1007` |
  | `v` is not 1 | `4004` |
  | Unknown `type` in v1 (versioning covers evolution, so this is a client bug) | `4005` |
  | Binary frame | `1003` (Spring's `TextWebSocketHandler`) |
  | Frame larger than the limit (Decision 3) | `1009` (sent by the container) |
- **Reply with an error frame and keep the socket open** when the envelope is fine but the content is invalid:
  - a missing or null `clientMsgId`: `VALIDATION_ERROR` with `clientMsgId: null`;
  - a bad `conversationId`;
  - a blank or too long body;
  - domain errors from the send.

  The frame carries the client's `clientMsgId` so it can match the reply to its request.
- **Codes reuse REST's:** `VALIDATION_ERROR`, `CONVERSATION_NOT_FOUND`, `IDEMPOTENCY_KEY_CONFLICT`, and `INTERNAL_ERROR` ("An unexpected error occurred", the same text as REST's 500). The message is the code's fixed default, except for `VALIDATION_ERROR`, whose detail only describes the client's own input.
- **"Doesn't exist" and "not a member"** give identical frames apart from `clientMsgId`, the same 404 rule as REST (`FrameCodecTest`, with a mutation check).

## Decision 3: explicit frame-size limit, 32 KiB
The text limit is `ws.max-text-frame-bytes` (default 32,768) and binary is 1 KiB. They are set as Tomcat servlet-context init parameters (`org.apache.tomcat.websocket.textBufferSize` / `binaryBufferSize`, from Tomcat's own constants), which Tomcat's WebSocket container reads at startup. Spring's `ServletServerContainerFactoryBean` was tried first, but it needs a live container and broke the mock-web context test. Tomcat's default (8 KiB) is too small: a 4,000-char body is up to 12,000 bytes in UTF-8, and a client may legally escape every character as `\uXXXX` (6 bytes), about 24,000 bytes plus the envelope. `WsProtocolTest` sends such a frame. It was closed with 1009 under the default limit, and is read and parsed under this one. The body limit itself (4,000 chars) is enforced in `MessageService` for REST and WebSocket alike.

## Decision 4: ack only after commit
The handler is not transactional and calls the `@Transactional` `MessageService.sendMessage`, the same method REST uses. Its transaction logic (membership, row lock, idempotency lookup, seq, insert) is not duplicated. When the call returns, the transaction has committed, so the ack is sent after the return. If the commit fails, the client gets an error frame and no ack. A duplicate `clientMsgId` (same conversation and body) returns the stored message, so the duplicate gets an identical ack.

## Decision 5: push after commit, off the request and socket threads
`sendMessage` publishes `MessageCreatedEvent` only when it inserts a new row, so duplicates are never pushed twice. Realtime handles it AFTER_COMMIT (as in ADR-0005) and queues the push on a bounded realtime executor. The push goes to every open socket of every active participant except the socket the message came from, which gets the ack instead. Messages sent over REST are pushed too.
- Every send to a socket goes through a `ConcurrentWebSocketSessionDecorator` (send-time and buffer limits), because concurrent sends to one session are unsafe. Tomcat's own blocking-send timeout is set per session to the same time limit.
- **A slow consumer** (limit exceeded, or a failed write) is closed with `4003` and forgotten; it recovers missed messages from history. The close goes through the raw session: once a `ConcurrentWebSocketSessionDecorator`'s limit has tripped, its own `close` rewrites any status to `4500` (Spring 6.1.13 source; a unit test fails if the close goes through the decorator).
- **A full push queue** drops the push, logs it and counts it; clients recover from history.

## Consequences
- **Out of order:** pushes may arrive out of `seq` order (after-commit handoffs can interleave). Clients order by `seq`; gap detection and resync are Phase 3.
- **Not guaranteed:** delivery to sockets is best effort, by design; history is the guarantee.
- **No rate limit:** there is no per-socket send rate limit yet.
- **Dropped pushes:** a full push queue drops pushes (logged and counted, `MessagePushListener.droppedPushes()`); clients recover from history.
- **Needs a transaction:** `@TransactionalEventListener` ignores events published without an active transaction (no push would happen). `sendMessage` is `@Transactional`; the REST-send and WebSocket-send push tests would fail if it were not, but no test publishes outside a transaction on purpose.
- **Single instance:** multi-instance push needs a Redis fan-out (Phase 4).
