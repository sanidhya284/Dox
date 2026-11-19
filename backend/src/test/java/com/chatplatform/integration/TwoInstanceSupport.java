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
        props.put("server.port", 0);
        props.put("spring.datasource.url", postgres.getJdbcUrl());
        props.put("spring.datasource.username", postgres.getUsername());
        props.put("spring.datasource.password", postgres.getPassword());
        props.put("jwt.secret", "this-is-a-very-long-secret-key-for-tests-only-must-be-32-bytes");
        props.put("spring.data.redis.host", "localhost");
        props.put("spring.data.redis.port", REDIS_PORT);
        props.put("realtime.bus", busModeB());
        props.put("management.metrics.use-global-registry", false);
        props.putAll(extraPropertiesB());
        // Command-line arguments, not default properties: defaults have the LOWEST precedence, so application.yml's
        // datasource URL (localhost:5432) would win and B would silently use another database.
        instanceB = new SpringApplicationBuilder(ChatPlatformApplication.class)
                .profiles("integration")
                .run(asArgs(props));
        if (!postgres.getJdbcUrl().equals(instanceB.getEnvironment().getProperty("spring.datasource.url"))) {
            instanceB.close();
            throw new IllegalStateException("instance B is not on the test database");
        }
        portB = ((WebServerApplicationContext) instanceB).getWebServer().getPort();
        restA = jdkRest(portA);
        restB = jdkRest(portB);
    }

    @AfterAll
    void stopInstanceB() {
        if (instanceB != null) {
            instanceB.close();
        }
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
