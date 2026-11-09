package com.chatplatform.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0011 Decision 10: a rollback deploys the PREVIOUS release against a schema a newer release already migrated.
 * That previous release sees an applied migration it doesn't have, and must still start: Flyway's validation (which
 * runs on every startup) has to accept "future" migrations. The negative form shows the same check fails when the
 * app does NOT ignore future migrations, so this test can fail.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class FutureMigrationToleranceTest extends AbstractIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void anOlderReleaseStillValidatesAgainstASchemaMigratedByANewerOne() {
        int rank = jdbc.sql("SELECT coalesce(max(installed_rank), 0) + 1 FROM flyway_schema_history")
                .query(Integer.class).single();
        // A newer release applied V9999, which this build doesn't contain.
        jdbc.sql("""
                INSERT INTO flyway_schema_history
                    (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                VALUES (:rank, '9999', 'future release', 'SQL', 'V9999__future_release.sql', 0, 'test', 0, true)
                """).param("rank", rank).update();
        try {
            assertThatCode(flyway::validate).as("the app's own Flyway configuration").doesNotThrowAnyException();

            Flyway strict = Flyway.configure().configuration(flyway.getConfiguration())
                    .ignoreMigrationPatterns("*:missing").load();
            assertThatThrownBy(strict::validate).as("negative form: not ignoring future migrations refuses to start")
                    .hasMessageContaining("9999");
        } finally {
            jdbc.sql("DELETE FROM flyway_schema_history WHERE installed_rank = :rank").param("rank", rank).update();
        }
    }
}
