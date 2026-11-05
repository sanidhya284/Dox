package com.chatplatform.load;

import com.chatplatform.client.ReferenceClient;
import com.chatplatform.load.RunClassifier.Observations;
import com.chatplatform.load.RunClassifier.Verdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Load harness entry point (Phase 5-lite; method in docs/BENCHMARKS.md). One invocation = one run of
 * one scenario at one open-loop rate: setup (outside the window), warm-up (discarded), measurement
 * window, drain, invariant checks, classification, result file. Prints exactly one summary line.
 *
 * <p>Arguments (key=value): scenario=A|B|C rate=&lt;sends/s&gt; warmup=PT30S window=PT60S drain=PT15S
 * groupSize=10 groups=10 fanUsers=20 socketsPerUser=10 lagMs=10 label=... out=../bench-results
 * base=http://localhost:8080 jdbc=jdbc:postgresql://localhost:55432/chat_bench.
 *
 * <p>Exit codes: 0 a result was written (VALID, SATURATED or INVALID), 3 invariant violation (a bug),
 * 2 bad arguments or the app/DB not reachable.
 */
public final class LoadMain {

    private static final String PASSWORD = "loadpass-123";

    private final Map<String, String> args;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // scenario
    private final String scenario;
    private final double rate;
    private final Duration warmup;
    private final Duration window;
    private final Duration drain;
    private final String base;
    private final int port;
    private final String runTag = UUID.randomUUID().toString().substring(0, 8);
    private final String bodyPrefix;

    // bookkeeping (written by the pacer before the matching send is queued; read on client threads)
    private long[] intended;
    private long windowStart;
    private long windowNanos;
    private final LatencyStats ack = new LatencyStats();
    private final LatencyStats[] ackThirds = {new LatencyStats(), new LatencyStats(), new LatencyStats()};
    private final LatencyStats recv = new LatencyStats();
    private final LatencyStats lag = new LatencyStats();        // intended -> frame written (total)
    private final LatencyStats pacerLag = new LatencyStats();   // intended -> handed to the client (pacer only)
    private final AtomicLong windowFirstSends = new AtomicLong();
    private final AtomicLong resends = new AtomicLong();
    private final AtomicLong outboxFull = new AtomicLong();
    private final Set<UUID> acked = ConcurrentHashMap.newKeySet();

    private LoadMain(Map<String, String> args) {
        this.args = args;
        this.scenario = arg("scenario", "A").toUpperCase(Locale.ROOT);
        this.rate = Double.parseDouble(arg("rate", "10"));
        this.warmup = Duration.parse(arg("warmup", "PT30S"));
        this.window = Duration.parse(arg("window", "PT60S"));
        this.drain = Duration.parse(arg("drain", "PT15S"));
        this.base = arg("base", "http://localhost:8080");
        this.port = URI.create(base).getPort();
        this.bodyPrefix = "L|" + runTag + "|";
        if (!Set.of("A", "B", "C").contains(scenario)) {
            throw new IllegalArgumentException("scenario must be A, B or C");
        }
    }

    public static void main(String[] argv) {
        Map<String, String> a = new HashMap<>();
        for (String s : argv) {
            int eq = s.indexOf('=');
            if (eq <= 0) {
                System.out.println("bad argument (want key=value): " + s);
                System.exit(2);
            }
            a.put(s.substring(0, eq), s.substring(eq + 1));
        }
        int code;
        try {
            code = new LoadMain(a).run();
        } catch (Exception e) {
            System.out.println("LOAD RUN FAILED: " + e);
            code = 2;
        }
        System.exit(code);
    }

    private String arg(String key, String def) {
        return args.getOrDefault(key, def);
    }

    private int intArg(String key, int def) {
        return Integer.parseInt(arg(key, String.valueOf(def)));
    }

    // ═════════════════════════════ run ═════════════════════════════

    private int run() throws Exception {
        String jdbc = arg("jdbc", "jdbc:postgresql://localhost:55432/chat_bench");
        String dbUser = arg("dbUser", "chat_bench");
        String dbPassword = arg("dbPassword", "chat_bench");
        ServerSampler sampler = new ServerSampler(base, arg("metricsPassword", "bench-only-metrics-password-123"),
                jdbc, dbUser, dbPassword);
        sampler.scrape(); // fail fast if the app is not up or the metrics password is wrong
        Map<String, Object> environment = Environment.capture(sampler, jdbc, dbUser, dbPassword, port, mapper);

        // A previous (overloaded) run may still be draining its backlog: start only on a quiet server.
        double quiescedAfter = awaitQuietServer(sampler, jdbc, dbUser, dbPassword);

        // ── setup (outside the window): users (BCrypt), conversations, sockets ──
        long setupStart = System.nanoTime();
        List<ReferenceClient> senders = new ArrayList<>();
        List<PushReceiver> receivers = new ArrayList<>();
        List<UUID> conversations = new ArrayList<>();
        Map<String, Object> shape = new LinkedHashMap<>();
        setup(senders, receivers, conversations, shape);
        double setupSeconds = (System.nanoTime() - setupStart) / 1e9;

        // ── pace: warm-up then window, open loop ──
        long total = (long) Math.ceil(rate * (warmup.toNanos() + window.toNanos()) / 1e9);
        intended = new long[(int) total];
        long t0 = System.nanoTime() + 200_000_000L;
        windowStart = t0 + warmup.toNanos();
        windowNanos = window.toNanos();
        OpenLoopSchedule schedule = new OpenLoopSchedule(t0, rate);
        sampler.start();
        long samplerStartNanos = System.nanoTime();
        // Window-start snapshot on its own thread, so the pacer is never delayed by a scrape.
        java.util.concurrent.CompletableFuture<PrometheusText> beforeF = new java.util.concurrent.CompletableFuture<>();
        AtomicLong gcBeforeRef = new AtomicLong();
        Thread snap = new Thread(() -> {
            sleepUntil(windowStart);
            gcBeforeRef.set(gcMillis());
            try {
                beforeF.complete(sampler.scrape());
            } catch (Exception e) {
                beforeF.complete(null);
            }
        }, "window-start-snapshot");
        snap.setDaemon(true);
        snap.start();
        int groupSize = senders.size();
        for (int i = 0; i < total; i++) {
            long t = schedule.intendedNanos(i);
            intended[i] = t;
            sleepUntil(t);
            if (inWindow(i)) {
                pacerLag.record(System.nanoTime() - t);
            }
            ReferenceClient sender = senders.get(i % groupSize);
            UUID conv = conversations.get((i / groupSize) % conversations.size());
            sender.sendAsync(conv, bodyPrefix + i);
        }
        sleepUntil(windowStart + windowNanos);
        long gcWindowMillis = gcMillis() - gcBeforeRef.get();
        PrometheusText after = sampler.scrape();
        PrometheusText before = beforeF.get(10, java.util.concurrent.TimeUnit.SECONDS);

        // ── drain: let acks and pushes for the window arrive ──
        long drainDeadline = System.nanoTime() + drain.toNanos();
        while (System.nanoTime() < drainDeadline && senders.stream().mapToInt(ReferenceClient::outboxSize).sum() > 0) {
            Thread.sleep(50);
        }
        Thread.sleep(1000);
        List<ServerSampler.Sample> all = sampler.stop();
        long winStartMs = (windowStart - samplerStartNanos) / 1_000_000;
        long winEndMs = winStartMs + window.toMillis();
        List<ServerSampler.Sample> inWindow = all.stream()
                .filter(s -> s.tMillis() >= winStartMs && s.tMillis() <= winEndMs).toList();
        long outboxRemaining = senders.stream().mapToInt(ReferenceClient::outboxSize).sum();
        List<Integer> receiverCloses = receivers.stream().map(PushReceiver::closeCode).filter(c -> c >= 0).toList();

        // ── invariants (hold in every outcome) ──
        // Freeze first: close the clients so no ack can arrive during the check, then wait until the
        // server has committed its backlog (row count stable). Comparing against a live acked set
        // reported false violations under overload (acks landing between the query and the compare).
        senders.forEach(ReferenceClient::close);
        receivers.forEach(PushReceiver::close);
        Set<UUID> ackedFrozen = Set.copyOf(acked);
        awaitStableRowCount(jdbc, dbUser, dbPassword, conversations);
        Map<String, Object> invariants = new LinkedHashMap<>();
        boolean[] ok = checkInvariants(jdbc, dbUser, dbPassword, conversations, ackedFrozen, outboxRemaining, invariants);

        // ── classify ──
        Map<String, Object> serverSummary = ServerSampler.summarize(inWindow);
        double achieved = windowFirstSends.get() / (windowNanos / 1e9);
        double lagP99 = lag.percentileMs(99).orElse(lag.maxMs());
        Observations obs = new Observations(rate, achieved, lagP99, gcWindowMillis / (double) window.toMillis(),
                (int) (long) (Long) serverSummary.get("hikariPendingSamples"), resends.get(), outboxRemaining,
                ackThirds[0].percentileMs(50).orElse(Double.NaN), ackThirds[2].percentileMs(50).orElse(Double.NaN),
                ok[0], ok[1]);
        double lagMs = Double.parseDouble(arg("lagMs", "10"));
        Verdict verdict = new RunClassifier(lagMs).classify(obs);

        // ── result file ──
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", scenario);
        result.put("label", arg("label", ""));
        result.put("runTag", runTag);
        result.put("startedAt", LocalDateTime.now().toString());
        result.put("outcome", verdict.outcome().name());
        result.put("reasons", verdict.reasons());
        result.put("params", Map.of("targetRate", rate, "warmup", warmup.toString(), "window", window.toString(),
                "drain", drain.toString(), "lagThresholdMs", lagMs, "clientAckTimeout", ackTimeout().toString(),
                "quiescedAfterSeconds", quiescedAfter));
        result.put("shape", shape);
        result.put("setupSeconds", round(setupSeconds));
        result.put("sends", Map.of("planned", total, "windowFirstSends", windowFirstSends.get(),
                "achievedRate", round(achieved), "resends", resends.get(), "outboxFull", outboxFull.get(),
                "outboxRemaining", outboxRemaining, "ackedDistinct", acked.size()));
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("note", "ms from the INTENDED send time; no expected-interval correction; "
                + "a percentile is n/a unless at least 10 samples lie above it");
        latency.put("sendToAck", describe(ack));
        latency.put("sendToReceive", describe(recv));
        latency.put("scheduleLag", describe(lag));
        latency.put("pacerLag", describe(pacerLag));
        latency.put("ackP50ByWindowThird", List.of(ackThirds[0].describe(50), ackThirds[1].describe(50),
                ackThirds[2].describe(50)));
        result.put("latencyMs", latency);
        result.put("harnessGcShare", round(gcWindowMillis / (double) window.toMillis()));
        result.put("server", serverSummary);
        result.put("serverCounterDeltas", counterDeltas(before, after));
        result.put("receiverCloseCodes", receiverCloses);
        result.put("invariants", invariants);
        result.put("samplerErrors", sampler.errors());
        result.put("samples", inWindow);
        result.put("environment", environment);

        File outDir = new File(arg("out", "../bench-results"));
        outDir.mkdirs();
        File file = new File(outDir, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + scenario + "-r" + (long) rate + (arg("label", "").isBlank() ? "" : "-" + arg("label", ""))
                + ".json");
        mapper.writeValue(file, result);

        System.out.println(String.format(Locale.ROOT,
                "%s rate=%.0f/s %s | achieved %.1f/s | ack p50=%s p99=%s max=%.1f ms | recv p99=%s | lag p99=%s"
                        + " | hikari pending s=%s | %s",
                scenario, rate, verdict.outcome(), achieved, ack.describe(50), ack.describe(99), ack.maxMs(),
                recv.describe(99), lag.describe(99), serverSummary.get("hikariPendingSamples"), file.getPath()));

        sampler.close();
        return verdict.outcome() == RunClassifier.Outcome.INVARIANT_VIOLATION ? 3 : 0;
    }

    // ═════════════════════════════ setup ═════════════════════════════

    private record User(UUID id, String username, String accessToken) {
    }

    private TestRestTemplate rest() {
        return new TestRestTemplate(new RestTemplateBuilder().rootUri(base));
    }

    private User register(TestRestTemplate rest, String prefix) throws Exception {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String name = prefix + s;
        ResponseEntity<String> r = rest.postForEntity("/api/auth/register", Map.of("username", name,
                "email", name + "@example.com", "password", PASSWORD, "displayName", "L " + s), String.class);
        if (r.getStatusCode().value() != 201) {
            throw new IllegalStateException("register failed: " + r.getStatusCode() + " " + r.getBody());
        }
        JsonNode n = mapper.readTree(r.getBody());
        return new User(UUID.fromString(n.path("user").path("id").asText()), name, n.path("accessToken").asText());
    }

    private UUID group(TestRestTemplate rest, User creator, List<User> members) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(creator.accessToken());
        List<String> ids = members.stream().filter(m -> !m.id().equals(creator.id())).map(m -> m.id().toString()).toList();
        ResponseEntity<String> r = rest.postForEntity("/api/conversations/group",
                new HttpEntity<>(Map.of("title", "load " + runTag, "memberIds", ids), h), String.class);
        if (r.getStatusCode().value() != 201) {
            throw new IllegalStateException("group failed: " + r.getStatusCode() + " " + r.getBody());
        }
        return UUID.fromString(mapper.readTree(r.getBody()).path("id").asText());
    }

    private void setup(List<ReferenceClient> senders, List<PushReceiver> receivers, List<UUID> conversations,
                       Map<String, Object> shape) throws Exception {
        TestRestTemplate rest = rest();
        List<User> senderUsers = new ArrayList<>();
        switch (scenario) {
            case "A", "B" -> {
                int g = intArg("groupSize", 10);
                int k = scenario.equals("A") ? 1 : intArg("groups", 10);
                List<User> users = new ArrayList<>();
                for (int i = 0; i < g; i++) {
                    users.add(register(rest, "ld" + scenario + i + "_"));
                }
                for (int i = 0; i < k; i++) {
                    conversations.add(group(rest, users.get(0), users)); // every user is in every group
                }
                senderUsers.addAll(users);
                shape.put("users", g);
                shape.put("conversations", k);
                shape.put("sockets", g);
                shape.put("senders", g);
                shape.put("pushesPerMessage", g - 1);
            }
            case "C" -> {
                int u = intArg("fanUsers", 20);
                int perUser = intArg("socketsPerUser", 10);
                User sender = register(rest, "ldCs_");
                List<User> members = new ArrayList<>();
                members.add(sender);
                for (int i = 0; i < u; i++) {
                    members.add(register(rest, "ldC" + i + "_"));
                }
                conversations.add(group(rest, sender, members));
                for (User m : members.subList(1, members.size())) { // one login (the registration) per user
                    for (int s = 0; s < perUser; s++) {
                        PushReceiver pr = new PushReceiver(mapper, this::onReceived);
                        pr.connect(port, ticket(rest, m));
                        receivers.add(pr);
                    }
                }
                senderUsers.add(sender);
                shape.put("users", u + 1);
                shape.put("conversations", 1);
                shape.put("receiverSockets", u * perUser);
                shape.put("socketsPerUser", perUser);
                shape.put("senders", 1);
                shape.put("pushesPerMessage", u * perUser);
            }
            default -> throw new IllegalStateException(scenario);
        }
        long seed = 1;
        for (User su : senderUsers) {
            ReferenceClient c = new ReferenceClient(su.username(), port, rest(), mapper, su.id(), su.username(),
                    PASSWORD, seed++, clientConfig(), Set.of());
            c.setObserver(observer());
            senders.add(c.start());
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (senders.stream().anyMatch(c -> !c.isConnected())) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("senders did not connect within 30 s");
            }
            Thread.sleep(50);
        }
    }

    private String ticket(TestRestTemplate rest, User u) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(u.accessToken());
        ResponseEntity<String> r = rest.exchange("/api/ws/ticket", HttpMethod.POST, new HttpEntity<>(h), String.class);
        return mapper.readTree(r.getBody()).path("ticket").asText();
    }

    /** Client ack timeout: default 5 s (production default); knee runs set it far above the window. */
    private Duration ackTimeout() {
        return Duration.parse(arg("ackTimeout", ReferenceClient.Config.defaults().ackTimeout().toString()));
    }

    private ReferenceClient.Config clientConfig() {
        ReferenceClient.Config d = ReferenceClient.Config.defaults();
        return new ReferenceClient.Config(ackTimeout(), d.antiEntropyInterval(), d.backoffBase(), d.backoffCap(),
                d.tick(), d.outboxCapacity());
    }

    /**
     * Waits (up to 180 s) for 3 consecutive one-second samples with no Hikari active or pending
     * connection and no active query on the bench database. Returns the seconds waited.
     */
    private static double awaitQuietServer(ServerSampler sampler, String jdbc, String user, String password)
            throws Exception {
        long start = System.nanoTime();
        long deadline = start + Duration.ofSeconds(180).toNanos();
        int quiet = 0;
        try (Connection c = DriverManager.getConnection(jdbc, user, password);
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname = "
                     + "current_database() AND pid <> pg_backend_pid() AND state <> 'idle'")) {
            while (quiet < 3) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("server not quiet after 180 s");
                }
                PrometheusText p = sampler.scrape();
                long busy;
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    busy = rs.getLong(1);
                }
                boolean idle = p.sum("hikaricp_connections_active") == 0 && p.sum("hikaricp_connections_pending") == 0
                        && busy == 0;
                quiet = idle ? quiet + 1 : 0;
                Thread.sleep(1000);
            }
        }
        return round((System.nanoTime() - start) / 1e9);
    }

    // ═════════════════════════════ observation ═════════════════════════════

    private int indexOf(String body) {
        if (body == null || !body.startsWith(bodyPrefix)) {
            return -1;
        }
        return Integer.parseInt(body.substring(bodyPrefix.length()));
    }

    private boolean inWindow(int i) {
        return i >= 0 && intended[i] >= windowStart && intended[i] < windowStart + windowNanos;
    }

    private void onReceived(String body, long at) {
        int i = indexOf(body);
        if (inWindow(i)) {
            recv.record(at - intended[i]);
        }
    }

    private ReferenceClient.Observer observer() {
        return new ReferenceClient.Observer() {
            @Override
            public void onFrameSent(UUID clientMsgId, String body, int previousAttempts, long nanos) {
                int i = indexOf(body);
                if (previousAttempts > 0) {
                    resends.incrementAndGet();
                } else if (inWindow(i)) {
                    windowFirstSends.incrementAndGet();
                    lag.record(nanos - intended[i]);
                }
            }

            @Override
            public void onAck(ReferenceClient.Msg msg, long receivedNanos) {
                acked.add(msg.clientMsgId());
                int i = indexOf(msg.body());
                if (inWindow(i)) {
                    long l = receivedNanos - intended[i];
                    ack.record(l);
                    int third = (int) Math.min(2, (intended[i] - windowStart) * 3 / windowNanos);
                    ackThirds[third].record(l);
                }
            }

            @Override
            public void onPush(ReferenceClient.Msg msg, long receivedNanos) {
                onReceived(msg.body(), receivedNanos);
            }

            @Override
            public void onOutboxFull(UUID conversationId, String body) {
                outboxFull.incrementAndGet();
            }
        };
    }

    // ═════════════════════════════ checks and helpers ═════════════════════════════

    /** Waits (up to 60 s) until the run's message count is unchanged across two reads 1 s apart. */
    private static void awaitStableRowCount(String jdbc, String user, String password, List<UUID> convs)
            throws Exception {
        try (Connection c = DriverManager.getConnection(jdbc, user, password);
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM messages WHERE conversation_id = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("uuid", convs.toArray()));
            long previous = -1;
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                long n;
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    n = rs.getLong(1);
                }
                if (n == previous) {
                    return;
                }
                previous = n;
                Thread.sleep(1000);
            }
        }
    }

    /**
     * [rowsMatchAcked, seqContiguous]; details go into {@code out}. With the clients closed: every acked
     * id is persisted (an ack is sent only after commit), no clientMsgId is stored twice, and rows
     * without an ack are at most the sends still unacked at the end (their ack was lost to the close);
     * when everything drained that means rows == distinct acked ids exactly.
     */
    private boolean[] checkInvariants(String jdbc, String user, String password, List<UUID> convs,
                                      Set<UUID> acked, long outboxRemaining, Map<String, Object> out) throws Exception {
        Set<UUID> rows = new HashSet<>();
        long rowCount = 0;
        boolean contiguous = true;
        List<String> perConversation = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbc, user, password)) {
            Array ids = c.createArrayOf("uuid", convs.toArray());
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT client_msg_id FROM messages WHERE conversation_id = ANY(?)")) {
                ps.setArray(1, ids);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add((UUID) rs.getObject(1));
                        rowCount++;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT c.id, c.last_seq, count(m.id), coalesce(min(m.seq), 0), coalesce(max(m.seq), 0) "
                            + "FROM conversations c LEFT JOIN messages m ON m.conversation_id = c.id "
                            + "WHERE c.id = ANY(?) GROUP BY c.id, c.last_seq")) {
                ps.setArray(1, ids);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long lastSeq = rs.getLong(2);
                        long n = rs.getLong(3);
                        long min = rs.getLong(4);
                        long max = rs.getLong(5);
                        boolean ok = n == max && n == lastSeq && (n == 0 || min == 1);
                        contiguous &= ok;
                        perConversation.add(String.format(Locale.ROOT, "rows=%d min=%d max=%d last_seq=%d %s",
                                n, min, max, lastSeq, ok ? "ok" : "GAP"));
                    }
                }
            }
        }
        // Rows beyond the acked ids can only be sends still unacked after the drain (acks lost to a close).
        boolean allAckedPersisted = rows.containsAll(acked);
        long unackedRows = rows.size() - acked.size();
        boolean rowsMatch = rowCount == rows.size() && allAckedPersisted
                && unackedRows >= 0 && unackedRows <= outboxRemaining;
        out.put("dbRows", rowCount);
        out.put("distinctAckedClientMsgIds", acked.size());
        out.put("everyAckedIdPersisted", allAckedPersisted);
        out.put("rowsWithoutAck", unackedRows);
        out.put("rowsMatchAcked", rowsMatch);
        out.put("seqContiguous", contiguous);
        out.put("perConversation", perConversation);
        return new boolean[]{rowsMatch, contiguous};
    }

    private static Map<String, Object> describe(LatencyStats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", s.count());
        m.put("p50", s.describe(50));
        m.put("p95", s.describe(95));
        m.put("p99", s.describe(99));
        m.put("max", round(s.maxMs()));
        return m;
    }

    private static Map<String, Object> counterDeltas(PrometheusText before, PrometheusText after) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (before == null || after == null) {
            return m;
        }
        m.put("messagesCreated", after.sum("chat_messages_sent_total", "outcome=\"created\"")
                - before.sum("chat_messages_sent_total", "outcome=\"created\""));
        m.put("messagesDuplicate", after.sum("chat_messages_sent_total", "outcome=\"duplicate\"")
                - before.sum("chat_messages_sent_total", "outcome=\"duplicate\""));
        m.put("messagesRejected", after.sum("chat_messages_sent_total", "outcome=\"rejected\"")
                - before.sum("chat_messages_sent_total", "outcome=\"rejected\""));
        m.put("pushDropped", after.sum("chat_push_dropped_total") - before.sum("chat_push_dropped_total"));
        m.put("slowConsumerCloses4003", after.sum("chat_ws_closes_total", "code=\"4003\"")
                - before.sum("chat_ws_closes_total", "code=\"4003\""));
        double n = after.sum("chat_ws_send_ack_seconds_count") - before.sum("chat_ws_send_ack_seconds_count");
        double sum = after.sum("chat_ws_send_ack_seconds_sum") - before.sum("chat_ws_send_ack_seconds_sum");
        m.put("serverAckMeanMs", n > 0 ? round(1000 * sum / n) : Double.NaN);
        m.put("headsRequests", after.sum("chat_heads_requests_total") - before.sum("chat_heads_requests_total"));
        return m;
    }

    private static long gcMillis() {
        long t = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            t += Math.max(0, gc.getCollectionTime());
        }
        return t;
    }

    /**
     * Park until the instant in slices of at most 1 ms (one long park can oversleep by milliseconds on
     * macOS, through timer coalescing), spinning only for the last ~0.2 ms.
     */
    private static void sleepUntil(long nanos) {
        while (true) {
            long d = nanos - System.nanoTime();
            if (d <= 0) {
                return;
            }
            if (d > 200_000) {
                LockSupport.parkNanos(Math.min(d - 100_000, 1_000_000));
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
