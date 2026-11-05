package com.chatplatform.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Captures the environment of a run into its result file (amendments 3 and 4): machine, OS, power and
 * thermal state, other running containers, Docker VM limits, Postgres settings, both JVMs, git commit,
 * and the app's effective configuration (from /actuator/env, secrets masked). A command that fails is
 * recorded as unavailable rather than guessed.
 */
final class Environment {

    private Environment() {
    }

    static Map<String, Object> capture(ServerSampler server, String jdbcUrl, String dbUser, String dbPassword,
                                       int appPort, ObjectMapper mapper) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("cpu", sh("sysctl", "-n", "machdep.cpu.brand_string"));
        env.put("logicalCpus", sh("sysctl", "-n", "hw.ncpu"));
        env.put("memBytes", sh("sysctl", "-n", "hw.memsize"));
        env.put("os", sh("sw_vers").replace('\n', ' '));
        env.put("power", sh("pmset", "-g", "batt").replace('\n', ' '));
        env.put("thermal", sh("pmset", "-g", "therm").replace('\n', ' ')); // CPU_Speed_Limit < 100 = throttled
        env.put("dockerVm", sh("docker", "info", "--format", "{{.OperatingSystem}}; cpus={{.NCPU}}; mem={{.MemTotal}}"));
        env.put("dockerNote", "Docker Desktop on macOS runs Postgres in a Linux VM; not representative of a Linux server.");
        env.put("runningContainers", List.of(sh("docker", "ps", "--format", "{{.Names}} ({{.Image}})").split("\n")));
        env.put("benchContainerLimits", sh("docker", "inspect", "chat-bench-pg", "--format",
                "NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}"));
        env.put("gitCommit", sh("git", "rev-parse", "HEAD"));
        env.put("gitDirtyFiles", sh("git", "status", "--porcelain").lines().count());
        env.put("harnessJvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                + " " + ManagementFactory.getRuntimeMXBean().getInputArguments());
        String pid = sh("lsof", "-ti", "tcp:" + appPort, "-sTCP:LISTEN").lines().findFirst().orElse("");
        env.put("appCommandLine", pid.isBlank() ? "unavailable: no listener on " + appPort
                : sh("ps", "-o", "command=", "-p", pid.strip()));
        env.put("postgres", postgresSettings(jdbcUrl, dbUser, dbPassword));
        env.put("appEffectiveConfig", effectiveConfig(server, mapper));
        return env;
    }

    private static Map<String, String> postgresSettings(String url, String user, String password) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            for (String s : List.of("server_version", "shared_buffers", "max_connections", "synchronous_commit",
                    "fsync", "wal_sync_method", "work_mem")) {
                try (ResultSet rs = st.executeQuery("SHOW " + s)) {
                    rs.next();
                    out.put(s, rs.getString(1));
                }
            }
        } catch (Exception e) {
            out.put("error", e.toString());
        }
        return out;
    }

    /** Properties from /actuator/env for the prefixes that shape a run; secrets masked. */
    private static Map<String, Object> effectiveConfig(ServerSampler server, ObjectMapper mapper) {
        Map<String, Object> out = new TreeMap<>();
        try {
            JsonNode env = mapper.readTree(server.get("/actuator/env"));
            out.put("activeProfiles", env.path("activeProfiles").toString());
            // property sources are listed in precedence order: the first one to define a key wins
            for (JsonNode source : env.path("propertySources")) {
                source.path("properties").fields().forEachRemaining(p -> {
                    String key = p.getKey();
                    if (relevant(key) && !out.containsKey(key)) {
                        String v = p.getValue().path("value").asText();
                        out.put(key, secret(key) ? "****" : v);
                    }
                });
            }
        } catch (Exception e) {
            out.put("error", e.toString());
        }
        return out;
    }

    private static boolean relevant(String key) {
        return key.startsWith("spring.datasource") || key.startsWith("spring.profiles") || key.startsWith("server.")
                || key.startsWith("ws.") || key.startsWith("logging.") || key.startsWith("management.")
                || key.startsWith("jwt.") || key.startsWith("metrics.") || key.startsWith("spring.threads")
                || key.equals("java.version") || key.equals("java.vm.name");
    }

    private static boolean secret(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        return k.contains("password") || k.contains("secret");
    }

    static String sh(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "unavailable: timeout";
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            return "unavailable: " + e.getMessage();
        }
    }
}
