package com.chatplatform.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom Actuator HealthIndicator that surfaces database connectivity.
 *
 * Spring Boot auto-configures a DataSource health check, but this class
 * shows you how to write your own — useful when you need to report
 * application-specific details (e.g., "migration pending", "read replica lag").
 *
 * The result of this check is merged into GET /actuator/health under
 * the key "database" (Spring derives the key from the bean name).
 */
@Component("database")
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DatabaseHealthChecker databaseHealthChecker;

    public DatabaseHealthIndicator(DatabaseHealthChecker databaseHealthChecker) {
        this.databaseHealthChecker = databaseHealthChecker;
    }

    @Override
    public Health health() {
        try {
            if (databaseHealthChecker.isDatabaseReachable()) {
                return Health.up()
                        .withDetail("connection", "ok")
                        .build();
            }
            return Health.down()
                    .withDetail("connection", "query returned unexpected result")
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("connection", "unreachable")
                    .build();
        }
    }
}
