package com.chatplatform.shared.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Refuses startup when the {@code prod} profile is combined with a profile that carries dev-only secrets
 * or relaxed limits ({@code dev}, {@code bench}). The production image doesn't even contain those files
 * (ADR-0011); this guard covers a jar built without that exclusion.
 */
@Component
public class ProfileGuard {

    static final List<String> FORBIDDEN_WITH_PROD = List.of("dev", "bench");

    public ProfileGuard(Environment environment) {
        check(environment.getActiveProfiles());
    }

    static void check(String[] activeProfiles) {
        List<String> active = Arrays.asList(activeProfiles);
        if (active.contains("prod")) {
            for (String forbidden : FORBIDDEN_WITH_PROD) {
                if (active.contains(forbidden)) {
                    throw new IllegalStateException("Refusing to start: profile 'prod' must not be combined with '"
                            + forbidden + "' (dev-only secrets and relaxed limits). Active: " + active);
                }
            }
        }
    }
}
