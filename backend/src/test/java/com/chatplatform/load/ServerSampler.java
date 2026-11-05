package com.chatplatform.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Samples the server once a second during a window (amendment 2: the row lock is a hypothesis):
 * Hikari active/pending, process and system CPU, push-executor queue, sockets, from the Prometheus
 * scrape; and pg_stat_activity (state, wait_event_type, wait_event) from the benchmark database.
 * Row-lock waits show up as {@code Lock:transactionid} or {@code Lock:tuple}.
 */
final class ServerSampler implements AutoCloseable {

    record Sample(long tMillis, double hikariActive, double hikariPending, double hikariMax, double processCpu,
                  double systemCpu, double pushQueued, double sockets, Map<String, Integer> pgActivity) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String baseUrl;
    private final String basicAuth;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final List<Sample> samples = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private ScheduledExecutorService exec;
    private Connection pg;
    private long startMillis;

    ServerSampler(String baseUrl, String metricsPassword, String jdbcUrl, String dbUser, String dbPassword) {
        this.baseUrl = baseUrl;
        this.basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(("metrics:" + metricsPassword).getBytes(StandardCharsets.UTF_8));
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
    }

    String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", basicAuth)
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException(path + " -> HTTP " + res.statusCode());
        }
        return res.body();
    }

    PrometheusText scrape() throws Exception {
        return PrometheusText.parse(get("/actuator/prometheus"));
    }

    void start() throws Exception {
        pg = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
        startMillis = System.currentTimeMillis();
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "server-sampler");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(this::sampleOnce, 0, 1, TimeUnit.SECONDS);
    }

    private void sampleOnce() {
        try {
            PrometheusText p = scrape();
            Map<String, Integer> activity = new TreeMap<>();
            try (Statement st = pg.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT coalesce(state, '-'), coalesce(wait_event_type, '-'), coalesce(wait_event, '-'), count(*) "
                            + "FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() "
                            + "GROUP BY 1, 2, 3")) {
                while (rs.next()) {
                    activity.put(rs.getString(1) + "|" + rs.getString(2) + ":" + rs.getString(3), rs.getInt(4));
                }
            }
            Sample s = new Sample(System.currentTimeMillis() - startMillis,
                    p.sum("hikaricp_connections_active"), p.sum("hikaricp_connections_pending"),
                    p.sum("hikaricp_connections_max"), p.sum("process_cpu_usage"), p.sum("system_cpu_usage"),
                    p.sum("executor_queued_tasks", "name=\"wsPushExecutor\""), p.sum("chat_ws_sessions"), activity);
            synchronized (samples) {
                samples.add(s);
            }
        } catch (Exception e) {
            synchronized (samples) {
                errors.add(e.toString());
            }
        }
    }

    List<Sample> stop() {
        if (exec != null) {
            exec.shutdownNow();
            try {
                exec.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (samples) {
            return List.copyOf(samples);
        }
    }

    List<String> errors() {
        synchronized (samples) {
            return List.copyOf(errors);
        }
    }

    /** Summary over samples: maxima, the number of seconds with pending > 0, and wait events seen. */
    static Map<String, Object> summarize(List<Sample> samples) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("samples", samples.size());
        out.put("hikariActiveMax", samples.stream().mapToDouble(Sample::hikariActive).max().orElse(Double.NaN));
        out.put("hikariPendingMax", samples.stream().mapToDouble(Sample::hikariPending).max().orElse(Double.NaN));
        out.put("hikariPendingSamples", samples.stream().filter(s -> s.hikariPending() > 0).count());
        out.put("processCpuMax", samples.stream().mapToDouble(Sample::processCpu).max().orElse(Double.NaN));
        out.put("processCpuMean", samples.stream().mapToDouble(Sample::processCpu).average().orElse(Double.NaN));
        out.put("systemCpuMax", samples.stream().mapToDouble(Sample::systemCpu).max().orElse(Double.NaN));
        out.put("pushQueuedMax", samples.stream().mapToDouble(Sample::pushQueued).max().orElse(Double.NaN));
        Map<String, Integer> waits = new TreeMap<>();      // connection-samples per state|wait event
        for (Sample s : samples) {
            s.pgActivity().forEach((k, v) -> waits.merge(k, v, Integer::sum));
        }
        out.put("pgActivityConnectionSamples", waits);
        return out;
    }

    @Override
    public void close() throws Exception {
        stop();
        if (pg != null) {
            pg.close();
        }
    }
}
