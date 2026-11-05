package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.client.ReferenceClient;
import com.chatplatform.client.ReferenceClient.Bug;
import com.chatplatform.client.ReferenceClient.Msg;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.conversation.dto.CreateGroupRequest;
import com.chatplatform.message.repository.MessageRepository;
import com.chatplatform.realtime.websocket.ConnectionRegistry;
import com.chatplatform.realtime.websocket.MessagePushListener;
import com.chatplatform.realtime.websocket.WsCloseStatuses;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Phase 3 convergence under faults (ADR-0008). 4 users x 2 reference clients send 30 messages each
 * into 3 shared conversations while a seeded fault injector drops connections, pushes and acks,
 * duplicates and delays sends, and fails commits. Then faults stop and, with NO forced sync, every
 * client must converge (bounded wait) to exactly the PostgreSQL rows of every conversation its user
 * belongs to: id, seq, senderId, clientMsgId and body; contiguous seq; no duplicates; no losses.
 *
 * <p>Membership layout: G1 = {U1, U2, U3}, G2 = {U2, U3, U4}, D1 = {U1, U4} (direct).
 *
 * <p>The seed decides every fault; thread scheduling still varies, so a seed reproduces the faults,
 * not the exact interleaving. CI runs seeds 11, 22, 33. Replay one: -Dconvergence.seed=N.
 * Negative variants (manual): -Dconvergence.bug=NO_DEDUPE (etc.) runs all clients with that bug.
 */
@TestPropertySource(properties = {"ws.push.threads=1", "ws.push.queue-capacity=2"})
@Timeout(value = 3, unit = TimeUnit.MINUTES)
public class ConvergenceTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "password123";
    private static final int CLIENTS_PER_USER = 2;
    private static final int SENDS_PER_CLIENT = 30;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MessagePushListener pushListener;

    @SpyBean
    private ConnectionRegistry registry;

    @SpyBean
    private MessageRepository messageRepository;

    static Stream<Long> seeds() {
        String replay = System.getProperty("convergence.seed");
        return replay != null ? Stream.of(Long.parseLong(replay)) : LongStream.of(11, 22, 33).boxed();
    }

    private record User(UUID id, String username, String accessToken) {
    }

    private User register(String prefix) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String username = prefix + s;
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(username, username + "@example.com", PASSWORD, "U " + s), AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new User(res.getBody().user().id(), username, res.getBody().accessToken());
    }

    private HttpHeaders bearer(User u) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(u.accessToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private UUID group(User owner, User... members) {
        List<UUID> ids = new ArrayList<>();
        for (User m : members) {
            ids.add(m.id());
        }
        return restTemplate.postForEntity("/api/conversations/group",
                new HttpEntity<>(new CreateGroupRequest("g", ids), bearer(owner)), ConversationResponse.class).getBody().id();
    }

    private UUID dm(User a, User b) {
        return restTemplate.postForEntity("/api/conversations/direct",
                new HttpEntity<>(new CreateDirectRequest(b.id()), bearer(a)), ConversationResponse.class).getBody().id();
    }

    private List<Msg> dbRows(UUID conv) {
        return jdbcClient.sql("SELECT id, conversation_id, seq, sender_id, client_msg_id, body FROM messages "
                        + "WHERE conversation_id = :c ORDER BY seq")
                .param("c", conv)
                .query((rs, n) -> new Msg(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                        rs.getLong("seq"), rs.getObject("sender_id", UUID.class), rs.getObject("client_msg_id", UUID.class),
                        rs.getString("body")))
                .list();
    }

    // ── the seeded fault injector ──

    private static final class FaultInjector {
        final AtomicBoolean on = new AtomicBoolean(true);
        /** Tail phase: drop EVERY push, so the last message of each conversation reaches nobody by push. */
        final AtomicBoolean dropAllPushes = new AtomicBoolean(false);
        final Random server;
        final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        final Set<UUID> commitFailedOnce = ConcurrentHashMap.newKeySet();
        final long seed;

        FaultInjector(long seed) {
            this.seed = seed;
            this.server = new Random(seed);
        }

        synchronized boolean roll(double p) {
            return server.nextDouble() < p;
        }

        void count(String fault) {
            counts.computeIfAbsent(fault, k -> new AtomicInteger()).incrementAndGet();
        }

        /** Per clientMsgId and seed, independent of call order: whether its first commit fails. */
        boolean failsFirstCommit(UUID clientMsgId) {
            return new Random(seed ^ clientMsgId.getMostSignificantBits() ^ clientMsgId.getLeastSignificantBits())
                    .nextDouble() < 0.10;
        }

        ReferenceClient.Faults clientFaults(int index) {
            Random r = new Random(seed * 31 + index);
            return new ReferenceClient.Faults() {
                @Override
                public boolean duplicateSend() {
                    boolean f = on.get() && r.nextDouble() < 0.10;
                    if (f) {
                        count("client duplicate send");
                    }
                    return f;
                }

                @Override
                public long delaySendMillis() {
                    if (on.get() && r.nextDouble() < 0.10) {
                        count("client delayed send");
                        return r.nextInt(40);
                    }
                    return 0;
                }

                @Override
                public boolean dropConnectionNow() {
                    boolean f = on.get() && r.nextDouble() < 0.01;
                    if (f) {
                        count("client dropped connection");
                    }
                    return f;
                }
            };
        }
    }

    private void installServerFaults(FaultInjector fi) {
        // Drop pushes silently (on top of the real push executor dropping with its 2-slot queue).
        doAnswer(inv -> {
            if (fi.dropAllPushes.get()) {
                fi.count("tail dropped push");
                return null;
            }
            if (fi.on.get() && fi.roll(0.20)) {
                fi.count("server dropped push");
                return null;
            }
            return inv.callRealMethod();
        }).when(registry).sendToUsers(anyCollection(), any(TextMessage.class), any());
        // Drop acks silently (pings and error frames untouched).
        doAnswer(inv -> {
            WebSocketMessage<?> m = inv.getArgument(2);
            if (fi.on.get() && m instanceof TextMessage t && t.getPayload().contains("\"type\":\"ack\"") && fi.roll(0.15)) {
                fi.count("server dropped ack");
                return true;
            }
            return inv.callRealMethod();
        }).when(registry).sendTo(any(UUID.class), anyString(), any(WebSocketMessage.class));
        // Fail the commit of a seeded 10% of sends, on their first attempt only (a retry succeeds).
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            UUID cmid = inv.getArgument(3);
            if (fi.on.get() && fi.failsFirstCommit(cmid) && fi.commitFailedOnce.add(cmid)) {
                fi.count("server failed commit");
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("injected failure before commit");
                    }
                });
            }
            return result;
        }).when(messageRepository).insert(any(UUID.class), anyLong(), any(UUID.class), any(UUID.class), anyString());
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void everyClientConvergesToTheDatabaseUnderFaults(long seed) throws Exception {
        String bugProp = System.getProperty("convergence.bug");
        Set<Bug> bugs = bugProp == null ? Set.of() : EnumSet.of(Bug.valueOf(bugProp));
        System.out.println("CONVERGENCE seed=" + seed + (bugs.isEmpty() ? "" : " bugs=" + bugs));
        Random layout = new Random(seed);

        User u1 = register("cvA_");
        User u2 = register("cvB_");
        User u3 = register("cvC_");
        User u4 = register("cvD_");
        UUID g1 = group(u1, u2, u3);
        UUID g2 = group(u2, u3, u4);
        UUID d1 = dm(u1, u4);
        Map<User, List<UUID>> memberships = new LinkedHashMap<>();
        memberships.put(u1, List.of(g1, d1));
        memberships.put(u2, List.of(g1, g2));
        memberships.put(u3, List.of(g1, g2));
        memberships.put(u4, List.of(g2, d1));

        FaultInjector fi = new FaultInjector(seed);
        installServerFaults(fi);
        long droppedBefore = pushListener.droppedPushes();

        List<ReferenceClient> clients = new ArrayList<>();
        Map<ReferenceClient, List<UUID>> convsOf = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<User, List<UUID>> e : memberships.entrySet()) {
            for (int i = 0; i < CLIENTS_PER_USER; i++) {
                User u = e.getKey();
                ReferenceClient c = new ReferenceClient(u.username() + "#" + i, port, restTemplate, mapper, u.id(),
                        u.username(), PASSWORD, seed * 1000 + index, ReferenceClient.Config.forTests(), bugs);
                c.setFaults(fi.clientFaults(index++));
                clients.add(c.start());
                convsOf.put(c, e.getValue());
            }
        }

        Thread serverCloser = new Thread(() -> {
            while (fi.on.get()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ex) {
                    return;
                }
                if (fi.roll(0.30)) {
                    User u = new ArrayList<>(memberships.keySet()).get((int) (fi.server.nextDouble() * 4) % 4);
                    List<String> ids = new ArrayList<>(registry.sessionIdsFor(u.id()));
                    if (!ids.isEmpty()) {
                        boolean slow = fi.roll(0.5);
                        registry.closeSession(u.id(), ids.get(0), slow ? WsCloseStatuses.SLOW_CONSUMER : WsCloseStatuses.IDLE_TIMEOUT);
                        fi.count(slow ? "server close 4003" : "server close 4006");
                    }
                }
            }
        }, "convergence-server-closer");
        serverCloser.setDaemon(true);
        serverCloser.start();

        try {
            // ── fault phase: all clients send concurrently ──
            ExecutorService pool = Executors.newFixedThreadPool(clients.size());
            CountDownLatch ready = new CountDownLatch(clients.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (ReferenceClient c : clients) {
                List<UUID> mine = convsOf.get(c);
                long sendSeed = layout.nextLong();
                futures.add(pool.submit(() -> {
                    Random r = new Random(sendSeed);
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    for (int i = 1; i <= SENDS_PER_CLIENT; i++) {
                        UUID conv = mine.get(r.nextInt(mine.size()));
                        assertThat(c.send(conv, c.name() + " m" + i)).as("outbox accepted").isPresent();
                        Thread.sleep(r.nextInt(25));
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();
            Thread.sleep(500); // let in-flight faults land

            // ── tail: the LAST message of each conversation is never pushed to anyone ──
            // Nothing is sent afterwards, so gap detection cannot fire: only anti-entropy can find these.
            fi.dropAllPushes.set(true);
            for (Map.Entry<UUID, User> tail : Map.of(g1, u1, g2, u2, d1, u4).entrySet()) {
                // Faults are still on (a commit may be failed once): resend with the SAME clientMsgId.
                UUID cmid = UUID.randomUUID();
                ResponseEntity<String> r = null;
                for (int attempt = 0; attempt < 3 && (r == null || !r.getStatusCode().is2xxSuccessful()); attempt++) {
                    r = restTemplate.postForEntity("/api/conversations/" + tail.getKey() + "/messages",
                            new HttpEntity<>(new com.chatplatform.message.dto.SendMessageRequest(cmid, "tail"),
                                    bearer(tail.getValue())), String.class);
                }
                assertThat(r.getStatusCode().is2xxSuccessful()).as("tail send; seed=" + seed).isTrue();
            }
            Thread.sleep(300); // let the (dropped) after-commit pushes run
            fi.dropAllPushes.set(false);

            // ── quiet phase: faults off, NO forced sync; wait (bounded) for convergence ──
            fi.on.set(false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            String diff = diff(clients, convsOf);
            while (diff != null && System.nanoTime() < deadline) {
                Thread.sleep(250);
                diff = diff(clients, convsOf);
            }

            int totalRows = dbRows(g1).size() + dbRows(g2).size() + dbRows(d1).size();
            String summary = "seed=" + seed + " bugs=" + bugs + " faults=" + fi.counts
                    + " executorDroppedPushes=" + (pushListener.droppedPushes() - droppedBefore)
                    + " reconnects=" + clients.stream().mapToInt(ReferenceClient::reconnects).sum()
                    + " totalRows=" + totalRows;
            System.out.println("CONVERGENCE " + summary);
            assertThat(diff).as("CONVERGENCE FAILED " + summary + "\n" + diff).isNull();
            assertThat(totalRows).as("every one of the sends stored exactly once (+3 tail messages); " + summary)
                    .isEqualTo(clients.size() * SENDS_PER_CLIENT + 3);
            for (ReferenceClient c : clients) {
                assertThat(c.state()).as(c.name() + " state; " + summary).isEqualTo(ReferenceClient.State.RUNNING);
                assertThat(c.terminalErrors()).as(c.name() + " terminal errors; " + summary).isZero();
            }
        } finally {
            fi.on.set(false);
            serverCloser.interrupt();
            clients.forEach(ReferenceClient::close);
        }
    }

    /** Null when every client matches the database for each of its conversations; otherwise a diff. */
    private String diff(List<ReferenceClient> clients, Map<ReferenceClient, List<UUID>> convsOf) {
        StringBuilder out = new StringBuilder();
        for (ReferenceClient c : clients) {
            if (c.outboxSize() > 0) {
                out.append(c.name()).append(": outbox not empty (").append(c.outboxSize()).append(")\n");
            }
            for (UUID conv : convsOf.get(c)) {
                List<Msg> db = dbRows(conv);
                List<Msg> mine = c.history(conv);
                if (!mine.equals(db)) {
                    Set<UUID> dbIds = new HashSet<>();
                    db.forEach(m -> dbIds.add(m.id()));
                    Set<UUID> myIds = new HashSet<>();
                    List<Long> dupSeqs = new ArrayList<>();
                    for (Msg m : mine) {
                        if (!myIds.add(m.id())) {
                            dupSeqs.add(m.seq());
                        }
                    }
                    List<Long> missing = db.stream().filter(m -> !myIds.contains(m.id())).map(Msg::seq).toList();
                    List<Long> extra = mine.stream().filter(m -> !dbIds.contains(m.id())).map(Msg::seq).toList();
                    out.append(c.name()).append(" conv ").append(conv).append(": db=").append(db.size())
                            .append(" client=").append(mine.size()).append(" missingSeqs=").append(missing)
                            .append(" extraSeqs=").append(extra).append(" duplicateSeqs=").append(dupSeqs).append('\n');
                }
            }
        }
        return out.isEmpty() ? null : out.toString();
    }
}
