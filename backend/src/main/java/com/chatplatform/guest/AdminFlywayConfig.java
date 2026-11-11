package com.chatplatform.guest;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Admin mode never migrates (ADR-0012, runbook 9a): it validates that the database schema matches this image's
 * migrations and refuses to start otherwise (for example an image older or newer than the live schema).
 */
@Configuration
@Profile("admin")
public class AdminFlywayConfig {

    @Bean
    FlywayMigrationStrategy validateOnly() {
        return flyway -> flyway.validate();
    }
}
