package com.chatplatform.integration;

import com.chatplatform.ChatPlatformApplication;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

/**
 * Two app instances in one JVM (ADR-0013): instance A is this class's Spring test context; instance B is a second
 * context started here, on its own port. Both share the singleton Postgres container and one singleton Redis
 * container. Each context has its own Micrometer registry (no global registry), so metrics never mix.
 *
 * <p>Redis is bound to a FIXED host port chosen at startup, so a restart of the container (step 2's outage test)
 * comes back on the same address.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"realtime.bus=redis", "management.metrics.use-global-registry=false"})
public abstract class TwoInstanceSupport extends AbstractIntegrationTest {

    static final int REDIS_PORT = freePort();

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no", "--maxmemory", "64mb",
                    "--maxmemory-policy", "noeviction")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))));

    static {
        redis.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> REDIS_PORT);
    }

    @LocalServerPort
    protected int portA;

    protected ConfigurableApplicationContext instanceB;
    protected int portB;
    protected TestRestTemplate restA;
    protected TestRestTemplate restB;

    /** The bus instance B uses; a subclass testing the local default overrides it (and A's via its own property). */
    protected String busModeB() {
        return "redis";
    }

    /** Extra properties for instance B (and, via @TestPropertySource, the subclass sets the same for A). */
    protected Map<String, Object> extraPropertiesB() {
        return Map.of();
    }

    @BeforeAll
    void startInstanceB() {
        Map<String, Object> props = new HashMap<>();
        props.put("realtime.bus", busModeB());
        props.putAll(extraPropertiesB());
        instanceB = startInstance(props);
        portB = ((WebServerApplicationContext) instanceB).getWebServer().getPort();
        restA = jdkRest(portA);
        restB = jdkRest(portB);
        // The bus subscription starts in the background (BusSubscription): wait until both are subscribed.
        for (org.springframework.context.ApplicationContext ctx : java.util.List.of(appContext, instanceB)) {
            ctx.getBeanProvider(org.springframework.data.redis.listener.RedisMessageListenerContainer.class)
                    .ifAvailable(c -> org.awaitility.Awaitility.await("bus subscription")
                            .atMost(java.time.Duration.ofSeconds(15)).until(c::isRunning));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext appContext;

    /**
     * Starts one more app instance on the test database and the test Redis, with {@code overrides} on top; the
     * caller closes it.
     */
    protected static ConfigurableApplicationContext startInstance(Map<String, Object> overrides) {
        return startInstance(overrides, new Class<?>[0]);
    }

    /** As above, with extra configuration classes (test-only beans) in the new instance. */
    protected static ConfigurableApplicationContext startInstance(Map<String, Object> overrides,
                                                                  Class<?>... extraSources) {
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", 0);
        props.put("spring.datasource.url", postgres.getJdbcUrl());
        props.put("spring.datasource.username", postgres.getUsername());
        props.put("spring.datasource.password", postgres.getPassword());
        props.put("jwt.secret", "this-is-a-very-long-secret-key-for-tests-only-must-be-32-bytes");
        props.put("spring.data.redis.host", "localhost");
        props.put("spring.data.redis.port", REDIS_PORT);
        props.put("realtime.bus", "redis");
        props.put("management.metrics.use-global-registry", false);
        props.putAll(overrides);
        // Command-line arguments, not default properties: defaults have the LOWEST precedence, so application.yml's
        // datasource URL (localhost:5432) would win and B would silently use another database.
        Class<?>[] sources = new Class<?>[extraSources.length + 1];
        sources[0] = ChatPlatformApplication.class;
        System.arraycopy(extraSources, 0, sources, 1, extraSources.length);
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(sources)
                .profiles("integration")
                .run(asArgs(props));
        if (!postgres.getJdbcUrl().equals(ctx.getEnvironment().getProperty("spring.datasource.url"))) {
            ctx.close();
            throw new IllegalStateException("the extra instance is not on the test database");
        }
        return ctx;
    }

    @AfterAll
    void stopInstanceB() {
        if (instanceB != null) {
            instanceB.close();
        }
    }

    // ── Helpers shared by the two-instance tests ─────────────────────────────────────────────────────────────

    @org.springframework.beans.factory.annotation.Autowired
    protected com.chatplatform.realtime.service.WsTicketService wsTickets;

    protected final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Registers a fresh user through {@code rest}; returns [id, accessToken, username]. */
    protected String[] newUser(TestRestTemplate rest, String prefix) {
        String name = prefix + java.util.UUID.randomUUID().toString().substring(0, 8);
        org.springframework.http.ResponseEntity<com.chatplatform.auth.dto.AuthResponse> r = rest.postForEntity(
                "/api/auth/register", new com.chatplatform.auth.dto.RegisterRequest(name, name + "@example.com",
                        "password123", name), com.chatplatform.auth.dto.AuthResponse.class);
        if (r.getStatusCode().value() != 201) {
            throw new IllegalStateException("register failed: " + r.getStatusCode());
        }
        return new String[]{r.getBody().user().id().toString(), r.getBody().accessToken(), name};
    }

    protected static org.springframework.http.HttpHeaders bearer(String token) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    protected org.springframework.http.ResponseEntity<String> post(TestRestTemplate rest, String path, String token,
                                                                 Object body) {
        return rest.exchange(path, org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body, bearer(token)), String.class);
    }

    protected org.springframework.http.ResponseEntity<String> get(TestRestTemplate rest, String path, String token) {
        return rest.exchange(path, org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(bearer(token)), String.class);
    }

    /** A group created by {@code token}'s user with {@code members}; returns its id. */
    protected String group(TestRestTemplate rest, String token, String... members) throws Exception {
        org.springframework.http.ResponseEntity<String> r = post(rest, "/api/conversations/group", token,
                Map.of("title", "x", "memberIds", java.util.List.of(members)));
        if (r.getStatusCode().value() != 201) {
            throw new IllegalStateException("group failed: " + r.getStatusCode());
        }
        return json.readTree(r.getBody()).get("id").asText();
    }

    /** REST send; returns the HTTP status code. */
    protected int send(TestRestTemplate rest, String token, String conversationId, String body) {
        return post(rest, "/api/conversations/" + conversationId + "/messages", token,
                Map.of("clientMsgId", java.util.UUID.randomUUID(), "body", body)).getStatusCode().value();
    }

    protected WsTestClient socket(int port, String userId) throws Exception {
        return WsTestClient.connect(port, wsTickets.issue(java.util.UUID.fromString(userId)).ticket());
    }

    static String[] asArgs(Map<String, Object> props) {
        return props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    /** JDK client: Apache's default retry honours Retry-After on 429 and would hide it (see HardeningTestSupport). */
    static TestRestTemplate jdkRest(int port) {
        return new TestRestTemplate(new RestTemplateBuilder()
                .requestFactory(() -> new JdkClientHttpRequestFactory())
                .rootUri("http://localhost:" + port));
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
