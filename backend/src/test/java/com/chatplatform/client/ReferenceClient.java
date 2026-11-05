package com.chatplatform.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Reference client for the sync protocol (ADR-0008), in test sources. It documents the algorithm and
 * is driven by the Phase 3 tests:
 * <ul>
 *   <li>Outbox with a stable clientMsgId per intent; resend on ack timeout (same socket) and after
 *       every reconnect; retryable vs terminal errors ({@link ErrorClass}).</li>
 *   <li>Reconnect with a fresh ticket and full-jitter backoff; close codes decide reconnect / re-login /
 *       stop-as-client-bug.</li>
 *   <li>SUBSCRIBE FIRST, then fetch forward from the last contiguous seq.</li>
 *   <li>Local history deduplicated by message id, ordered by seq; a push beyond contiguousSeq + 1
 *       triggers a fetch (gap detection); periodic jittered anti-entropy via /heads catches a dropped
 *       LAST push.</li>
 *   <li>An ack records the client's own message (body from the outbox entry).</li>
 *   <li>A 401 on REST triggers one single-flight token refresh and one retry.</li>
 * </ul>
 * All state changes run on one event-loop thread per client, so the state needs no locks.
 * {@link Bug} flags switch on exactly one wrong behaviour each, for the negative tests.
 */
public final class ReferenceClient implements AutoCloseable {

    public enum Bug { NO_DEDUPE, FETCH_BEFORE_SUBSCRIBE, NO_GAP_DETECTION, NEW_ID_ON_RETRY, LOOP_ON_PROTOCOL_ERROR, NO_ANTI_ENTROPY }

    public enum State { RUNNING, LOGGED_OUT, CLIENT_BUG, SERVER_BUG, TOO_MANY_SOCKETS, STOPPED }

    public record Msg(UUID id, UUID conversationId, long seq, UUID senderId, UUID clientMsgId, String body) {
    }

    public record Config(Duration ackTimeout, Duration antiEntropyInterval, Duration backoffBase, Duration backoffCap,
                         Duration tick, int outboxCapacity) {
        /** Production-like defaults (ADR-0008). */
        public static Config defaults() {
            return new Config(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(100),
                    Duration.ofSeconds(10), Duration.ofMillis(100), 1000);
        }

        /** Scaled down for tests. */
        public static Config forTests() {
            return new Config(Duration.ofMillis(400), Duration.ofMillis(600), Duration.ofMillis(20),
                    Duration.ofMillis(300), Duration.ofMillis(20), 1000);
        }
    }

    /**
     * Load-harness hook (Phase 5-lite): callbacks on the client's loop thread with System.nanoTime()
     * stamps. Receive stamps are taken when the socket handler gets the frame, before the loop queue.
     * Observing changes nothing about the client's behaviour; the default does nothing.
     */
    public interface Observer {
        /** A send frame was written; {@code previousAttempts} > 0 means a resend (ack timeout, reconnect, retryable error). */
        default void onFrameSent(UUID clientMsgId, String body, int previousAttempts, long nanos) { }
        default void onAck(Msg msg, long receivedNanos) { }
        default void onPush(Msg msg, long receivedNanos) { }
        default void onOutboxFull(UUID conversationId, String body) { }
        Observer NONE = new Observer() { };
    }

    /** Client-side fault hook (Phase 3 step 4); the default injects nothing. */
    public interface Faults {
        default boolean duplicateSend() { return false; }
        default long delaySendMillis() { return 0; }
        default boolean dropConnectionNow() { return false; }
        Faults NONE = new Faults() { };
    }

    private static final class ConvState {
        final List<Msg> history = new ArrayList<>();      // may contain duplicates only under Bug.NO_DEDUPE
        final Set<UUID> ids = new HashSet<>();
        final TreeMap<Long, Msg> bySeq = new TreeMap<>();
        long contiguousSeq;
    }

    // ── fixed collaborators ──
    private final String name;
    private final int port;
    private final TestRestTemplate rest;
    private final ObjectMapper mapper;
    private final UUID userId;
    private final String username;
    private final String password;
    private final Config cfg;
    private final Set<Bug> bugs;
    private final Random random;
    private final Backoff backoff;
    private final Outbox outbox;
    private volatile Faults faults = Faults.NONE;
    private volatile Observer observer = Observer.NONE;

    // ── auth (any thread) ──
    private volatile String accessToken;
    private volatile String refreshToken;
    private final Object refreshLock = new Object();
    private final AtomicInteger refreshes = new AtomicInteger();

    // ── event loop ──
    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final Thread loop;
    private volatile boolean running = true;

    // ── loop-thread state ──
    private final Map<UUID, ConvState> conversations = new HashMap<>();
    private WebSocketSession session;
    private int generation;
    private boolean connected;
    private long reconnectAtNanos = -1;
    private long nextAntiEntropyNanos;
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
    private final AtomicInteger reconnects = new AtomicInteger();
    private final AtomicInteger terminalErrors = new AtomicInteger();

    // ── test hooks ──
    private final AtomicInteger dropNextPushes = new AtomicInteger();
    private final AtomicInteger dropNextAcks = new AtomicInteger();
    private volatile Runnable betweenSubscribeAndFetch = () -> { };

    public ReferenceClient(String name, int port, TestRestTemplate rest, ObjectMapper mapper, UUID userId,
                           String username, String password, long seed, Config cfg, Set<Bug> bugs) {
        this.name = name;
        this.port = port;
        this.rest = rest;
        this.mapper = mapper;
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.cfg = cfg;
        this.bugs = bugs.isEmpty() ? EnumSet.noneOf(Bug.class) : EnumSet.copyOf(bugs);
        this.random = new Random(seed);
        this.backoff = new Backoff(cfg.backoffBase(), cfg.backoffCap(), new Random(seed ^ 0x5DEECE66DL));
        this.outbox = new Outbox(cfg.outboxCapacity());
        this.loop = new Thread(this::runLoop, "ref-client-" + name);
        this.loop.setDaemon(true);
    }

    // ═════════════════════════════ public API (any thread) ═════════════════════════════

    /** Logs in (own token family), then connects and syncs on the loop thread. */
    public ReferenceClient start() {
        login();
        loop.start();
        post(this::connectAndSync);
        return this;
    }

    /** Queues a send; empty if the outbox is full. Returns the clientMsgId. */
    public Optional<UUID> send(UUID conversationId, String body) {
        return call(() -> {
            Optional<Outbox.Entry> e = outbox.enqueue(conversationId, body);
            e.ifPresent(x -> flushDue());
            return e.map(Outbox.Entry::clientMsgId);
        });
    }

    /**
     * Non-blocking send for the load harness: queued to the loop thread, which enqueues it in the
     * outbox and flushes as {@link #send} does. A full outbox is reported to the observer.
     */
    public void sendAsync(UUID conversationId, String body) {
        post(() -> {
            if (outbox.enqueue(conversationId, body).isPresent()) {
                flushDue();
            } else {
                observer.onOutboxFull(conversationId, body);
            }
        });
    }

    public void setObserver(Observer observer) {
        this.observer = (observer == null) ? Observer.NONE : observer;
    }

    /** The client's history for a conversation, ordered by seq. */
    public List<Msg> history(UUID conversationId) {
        return call(() -> {
            ConvState st = conversations.get(conversationId);
            if (st == null) {
                return List.<Msg>of();
            }
            List<Msg> copy = new ArrayList<>(st.history);
            copy.sort(Comparator.comparingLong(Msg::seq));
            return copy;
        });
    }

    public long contiguousSeq(UUID conversationId) {
        return call(() -> {
            ConvState st = conversations.get(conversationId);
            return st == null ? 0L : st.contiguousSeq;
        });
    }

    public int outboxSize() {
        return outbox.size();
    }

    public State state() {
        return state.get();
    }

    public int reconnects() {
        return reconnects.get();
    }

    public int refreshes() {
        return refreshes.get();
    }

    public int terminalErrors() {
        return terminalErrors.get();
    }

    public String name() {
        return name;
    }

    public UUID userId() {
        return userId;
    }

    public boolean isConnected() {
        return call(() -> connected);
    }

    public void setFaults(Faults faults) {
        this.faults = faults;
    }

    // ── test hooks ──
    public void dropNextPushes(int n) {
        dropNextPushes.addAndGet(n);
    }

    public void dropNextAcks(int n) {
        dropNextAcks.addAndGet(n);
    }

    public void betweenSubscribeAndFetch(Runnable hook) {
        this.betweenSubscribeAndFetch = hook;
    }

    /** Deliver a frame as if it came from the socket (e.g. a duplicate push). */
    public void injectFrame(String text) {
        int gen = call(() -> generation);
        post(() -> onFrame(text, gen));
    }

    /** Send a raw frame on the current socket (e.g. a protocol violation). */
    public void sendRaw(String text) {
        post(() -> sendFrame(text));
    }

    /** Make the access token invalid (as if expired); the next REST call must refresh. */
    public void tamperAccessToken() {
        accessToken = accessToken + "tampered";
    }

    /** REST /heads from any thread (used by the single-flight refresh test). */
    public List<JsonNode> fetchHeads() {
        ResponseEntity<String> r = rest(HttpMethod.GET, "/api/conversations/heads", null);
        if (!r.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(name + ": heads failed " + r.getStatusCode());
        }
        return readArray(r.getBody());
    }

    @Override
    public void close() {
        running = false;
        post(() -> {
            state.compareAndSet(State.RUNNING, State.STOPPED);
            closeSocket();
        });
        try {
            loop.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ═════════════════════════════ event loop ═════════════════════════════

    private void post(Runnable r) {
        tasks.add(r);
    }

    private <T> T call(Supplier<T> s) {
        if (Thread.currentThread() == loop) {
            return s.get();
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        post(() -> {
            try {
                f.complete(s.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(name + ": loop call failed", e);
        }
    }

    private void runLoop() {
        nextAntiEntropyNanos = now() + jittered(cfg.antiEntropyInterval());
        while (running || !tasks.isEmpty()) {
            try {
                Runnable r = tasks.poll(cfg.tick().toMillis(), TimeUnit.MILLISECONDS);
                if (r != null) {
                    r.run();
                }
                if (running) {
                    onTick();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A failed REST call or send must not kill the client; it retries on later ticks.
                if (connected) {
                    // keep going
                }
            }
        }
    }

    private void onTick() {
        if (state.get() != State.RUNNING) {
            return;
        }
        long now = now();
        if (!connected && reconnectAtNanos >= 0 && now >= reconnectAtNanos) {
            reconnectAtNanos = -1;
            reconnects.incrementAndGet();
            connectAndSync();
            return;
        }
        if (connected && faults.dropConnectionNow()) {
            closeSocket(); // client-side connection drop; onClosed schedules the reconnect
            connected = false;
            scheduleReconnect();
            return;
        }
        flushDue();
        if (!bugs.contains(Bug.NO_ANTI_ENTROPY) && connected && now >= nextAntiEntropyNanos) {
            nextAntiEntropyNanos = now + jittered(cfg.antiEntropyInterval());
            syncAll();
        }
    }

    // ═════════════════════════════ connect and sync ═════════════════════════════

    private void connectAndSync() {
        if (state.get() != State.RUNNING) {
            return;
        }
        if (bugs.contains(Bug.FETCH_BEFORE_SUBSCRIBE)) {
            syncAll();
            betweenSubscribeAndFetch.run();
            connect();
        } else {
            connect();                        // subscribe first: pushes start arriving now
            betweenSubscribeAndFetch.run();
            syncAll();                        // then fetch whatever was committed before
        }
        if (connected) {
            for (Outbox.Entry e : outbox.pending()) {
                outbox.markSent(e.clientMsgId(), 0); // unacked entries are due immediately on the new socket
            }
            flushDue();
        }
    }

    private void connect() {
        ResponseEntity<String> t = rest(HttpMethod.POST, "/api/ws/ticket", null);
        if (!t.getStatusCode().is2xxSuccessful()) {
            scheduleReconnect();
            return;
        }
        String ticket = readTree(t.getBody()).get("ticket").asText();
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxTextMessageBufferSize(64 * 1024);
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.setOrigin("http://localhost:3000");
            int gen = ++generation;
            session = new StandardWebSocketClient(container)
                    .execute(new Handler(gen), headers, URI.create("ws://localhost:" + port + "/ws?ticket=" + ticket))
                    .get(5, TimeUnit.SECONDS);
            connected = true;
            backoff.reset();
        } catch (Exception e) {
            connected = false;
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (state.get() == State.RUNNING && reconnectAtNanos < 0) {
            reconnectAtNanos = now() + backoff.nextDelay().toNanos();
        }
    }

    private void closeSocket() {
        WebSocketSession s = session;
        if (s != null && s.isOpen()) {
            try {
                s.close(CloseStatus.GOING_AWAY);
            } catch (Exception ignored) {
                // already gone
            }
        }
    }

    /** For each conversation the server says I am in: fetch forward if behind. */
    private void syncAll() {
        ResponseEntity<String> r = rest(HttpMethod.GET, "/api/conversations/heads", null);
        if (!r.getStatusCode().is2xxSuccessful()) {
            return;
        }
        for (JsonNode head : readArray(r.getBody())) {
            UUID conv = UUID.fromString(head.get("conversationId").asText());
            long lastSeq = head.get("lastSeq").asLong();
            ConvState st = conversations.computeIfAbsent(conv, c -> new ConvState());
            if (lastSeq > st.contiguousSeq) {
                fetchForward(conv);
            }
        }
    }

    private void fetchForward(UUID conv) {
        long after = conversations.computeIfAbsent(conv, c -> new ConvState()).contiguousSeq;
        while (true) {
            ResponseEntity<String> r = rest(HttpMethod.GET,
                    "/api/conversations/" + conv + "/messages?after=" + after + "&limit=100", null);
            if (!r.getStatusCode().is2xxSuccessful()) {
                return;
            }
            JsonNode page = readTree(r.getBody());
            long last = after;
            for (JsonNode m : page.get("messages")) {
                Msg msg = toMsg(m);
                merge(msg);
                last = msg.seq();
            }
            if (!page.get("hasMore").asBoolean()) {
                return;
            }
            after = last;
        }
    }

    private void merge(Msg msg) {
        ConvState st = conversations.computeIfAbsent(msg.conversationId(), c -> new ConvState());
        if (st.ids.contains(msg.id()) && !bugs.contains(Bug.NO_DEDUPE)) {
            return;
        }
        st.history.add(msg);
        st.ids.add(msg.id());
        st.bySeq.putIfAbsent(msg.seq(), msg);
        while (st.bySeq.containsKey(st.contiguousSeq + 1)) {
            st.contiguousSeq++;
        }
    }

    // ═════════════════════════════ sending ═════════════════════════════

    private void flushDue() {
        if (!connected) {
            return;
        }
        long now = now();
        for (Outbox.Entry e : outbox.dueForRetry(now, cfg.ackTimeout().toNanos())) {
            Outbox.Entry toSend = e;
            if (bugs.contains(Bug.NEW_ID_ON_RETRY) && e.attempts() > 0) {
                outbox.failed(e.clientMsgId());
                toSend = outbox.enqueue(e.conversationId(), e.body()).orElse(e);
            }
            String frame = "{\"v\":1,\"type\":\"send\",\"conversationId\":\"" + toSend.conversationId()
                    + "\",\"clientMsgId\":\"" + toSend.clientMsgId() + "\",\"body\":" + quote(toSend.body()) + "}";
            long delay = faults.delaySendMillis();
            if (delay > 0) {
                sleep(delay);
            }
            sendFrame(frame);
            observer.onFrameSent(toSend.clientMsgId(), toSend.body(), toSend.attempts(), now());
            if (faults.duplicateSend()) {
                sendFrame(frame); // same clientMsgId twice: the server must store one row
            }
            outbox.markSent(toSend.clientMsgId(), now());
        }
    }

    private void sendFrame(String text) {
        WebSocketSession s = session;
        if (s == null || !s.isOpen()) {
            return;
        }
        try {
            s.sendMessage(new TextMessage(text));
        } catch (Exception e) {
            // the close callback will reconnect
        }
    }

    // ═════════════════════════════ inbound ═════════════════════════════

    private final class Handler extends TextWebSocketHandler {
        private final int gen;

        Handler(int gen) {
            this.gen = gen;
        }

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) {
            String text = message.getPayload();
            long receivedNanos = now();
            post(() -> onFrame(text, gen, receivedNanos));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            post(() -> onClosed(status.getCode(), gen));
        }
    }

    private void onFrame(String text, int gen) {
        onFrame(text, gen, now());
    }

    private void onFrame(String text, int gen, long receivedNanos) {
        if (gen != generation) {
            return; // from an old socket
        }
        JsonNode f = readTree(text);
        switch (f.get("type").asText()) {
            case "ack" -> {
                if (dropNextAcks.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    return;
                }
                UUID cmid = UUID.fromString(f.get("clientMsgId").asText());
                outbox.acked(cmid).ifPresent(entry -> {
                    Msg own = new Msg(UUID.fromString(f.get("messageId").asText()), entry.conversationId(),
                            f.get("seq").asLong(), userId, cmid, entry.body());
                    merge(own);
                    observer.onAck(own, receivedNanos);
                });
            }
            case "message" -> {
                if (dropNextPushes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    return;
                }
                Msg msg = toMsg(f.get("message"));
                observer.onPush(msg, receivedNanos);
                long before = conversations.computeIfAbsent(msg.conversationId(), c -> new ConvState()).contiguousSeq;
                merge(msg);
                if (!bugs.contains(Bug.NO_GAP_DETECTION) && msg.seq() > before + 1) {
                    fetchForward(msg.conversationId()); // never guess: fetch the gap
                }
            }
            case "error" -> {
                JsonNode cmidNode = f.get("clientMsgId");
                if (cmidNode == null || cmidNode.isNull()) {
                    return;
                }
                UUID cmid = UUID.fromString(cmidNode.asText());
                if (ErrorClass.of(f.get("code").asText()) == ErrorClass.TERMINAL) {
                    outbox.failed(cmid).ifPresent(e -> terminalErrors.incrementAndGet());
                } else {
                    outbox.markSent(cmid, 0); // retryable: due again on the next tick
                }
            }
            default -> {
                // cursor frames and anything newer: not needed for message sync
            }
        }
    }

    private void onClosed(int code, int gen) {
        if (gen != generation) {
            return;
        }
        connected = false;
        switch (code) {
            case 4002 -> state.set(State.LOGGED_OUT);                        // re-login required
            case 1007, 4004, 4005 -> {
                if (bugs.contains(Bug.LOOP_ON_PROTOCOL_ERROR)) {
                    scheduleReconnect();
                } else {
                    state.set(State.CLIENT_BUG);                             // never loop on our own bug
                }
            }
            case 1008 -> state.set(State.SERVER_BUG);
            case 4007 -> state.set(State.TOO_MANY_SOCKETS);                  // ADR-0006 2a: never auto-reconnect
            default -> scheduleReconnect();                                  // 4001, 4003, 4006, 1001, 1006, ...
        }
    }

    // ═════════════════════════════ REST with single-flight refresh ═════════════════════════════

    private void login() {
        ResponseEntity<String> r = rest.postForEntity("/api/auth/login",
                new HttpEntity<>("{\"username\":" + quote(username) + ",\"password\":" + quote(password) + "}", json(null)),
                String.class);
        if (!r.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(name + ": login failed " + r.getStatusCode());
        }
        JsonNode t = readTree(r.getBody());
        accessToken = t.get("accessToken").asText();
        refreshToken = t.get("refreshToken").asText();
    }

    private ResponseEntity<String> rest(HttpMethod method, String url, Object body) {
        String used = accessToken;
        ResponseEntity<String> r = exchange(method, url, body, used);
        if (r.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            if (refreshOnce(used)) {
                r = exchange(method, url, body, accessToken);
            }
        }
        return r;
    }

    private ResponseEntity<String> exchange(HttpMethod method, String url, Object body, String token) {
        return rest.exchange(url, method, new HttpEntity<>(body, json(token)), String.class);
    }

    /**
     * Single flight: concurrent callers that saw a 401 with the same token wait for ONE refresh and
     * then reuse its result. Returns false if the refresh failed (re-login required).
     */
    private boolean refreshOnce(String failedToken) {
        synchronized (refreshLock) {
            if (!failedToken.equals(accessToken)) {
                return true; // someone else already refreshed
            }
            ResponseEntity<String> r = rest.postForEntity("/api/auth/refresh",
                    new HttpEntity<>("{\"refreshToken\":" + quote(refreshToken) + "}", json(null)), String.class);
            if (!r.getStatusCode().is2xxSuccessful()) {
                state.set(State.LOGGED_OUT);
                return false;
            }
            JsonNode t = readTree(r.getBody());
            refreshToken = t.get("refreshToken").asText();
            accessToken = t.get("accessToken").asText();
            refreshes.incrementAndGet();
            return true;
        }
    }

    // ═════════════════════════════ helpers ═════════════════════════════

    private HttpHeaders json(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    private Msg toMsg(JsonNode m) {
        return new Msg(UUID.fromString(m.get("id").asText()), UUID.fromString(m.get("conversationId").asText()),
                m.get("seq").asLong(), UUID.fromString(m.get("senderId").asText()),
                UUID.fromString(m.get("clientMsgId").asText()), m.get("body").asText());
    }

    private JsonNode readTree(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(name + ": bad JSON " + s, e);
        }
    }

    private List<JsonNode> readArray(String s) {
        List<JsonNode> out = new ArrayList<>();
        readTree(s).forEach(out::add);
        return out;
    }

    private String quote(String s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long jittered(Duration d) {
        return (long) (d.toNanos() * (0.5 + random.nextDouble())); // 0.5x .. 1.5x: clients do not poll in lockstep
    }

    private static long now() {
        return System.nanoTime();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Conversations this client has seen (from heads), for the convergence assertions. */
    public Collection<UUID> knownConversations() {
        return call(() -> List.copyOf(conversations.keySet()));
    }
}
