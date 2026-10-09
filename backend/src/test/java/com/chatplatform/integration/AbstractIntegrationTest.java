package com.chatplatform.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("chatplatform_test")
            .withUsername("test")
            .withPassword("test");

    // Singleton container: started once per JVM, shared by all integration test classes, cleaned up by Testcontainers Ryuk at JVM exit. Do not use @Container here: it stops the container after each class while Spring caches the context.
    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Force Flyway to run in the integration profile
        registry.add("spring.flyway.enabled", () -> "true");
        // Provide a secret for JWT
        registry.add("jwt.secret", () -> "this-is-a-very-long-secret-key-for-tests-only-must-be-32-bytes");
    }

    @Autowired
    protected TestRestTemplate restTemplate;
}
