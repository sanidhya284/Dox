package com.chatplatform.health;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Verifies that the application can reach the database.
 *
 * Spring Boot Actuator's built-in DataSourceHealthIndicator already checks
 * connectivity; this class demonstrates how to inject JdbcClient correctly
 * and is the foundation for custom health checks we'll add in later milestones
 * (e.g., checking Flyway migration status, message queue depth).
 */
@Component
public class DatabaseHealthChecker {

    private final JdbcClient jdbcClient;

    public DatabaseHealthChecker(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Returns true if the database can answer a trivial query.
     * Called by DatabaseHealthIndicator.
     */
    public boolean isDatabaseReachable() {
        Integer result = jdbcClient
                .sql("SELECT 1")
                .query(Integer.class)
                .single();
        return Integer.valueOf(1).equals(result);
    }
}
