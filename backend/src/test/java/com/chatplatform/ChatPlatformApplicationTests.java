package com.chatplatform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: verifies the Spring ApplicationContext loads without errors.
 *
 * This test runs against the 'test' profile, which provides an
 * in-memory H2 datasource so PostgreSQL is not required during unit testing.
 * Integration tests (using Testcontainers + real PostgreSQL) will live in
 * a separate package introduced in Milestone 1.
 *
 * Learning note: @SpringBootTest starts a FULL ApplicationContext, which is
 * expensive. For unit tests on individual classes, prefer plain @ExtendWith
 * (MockitoExtension.class) without @SpringBootTest.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatPlatformApplicationTests {

    @Test
    void contextLoads() {
        // If the context fails to start, this test fails with a clear error.
    }
}
