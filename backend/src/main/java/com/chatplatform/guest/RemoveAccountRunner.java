package com.chatplatform.guest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runbook 9a, "remove an abusive account" (ADR-0012). Runs only in the explicit admin mode: profiles
 * {@code prod,admin} plus {@code --remove-account=<username>}. It removes the account with the same step as the
 * guest cleanup, then exits:
 * <ul>
 *   <li>{@code 0}: removed;</li>
 *   <li>{@code 3}: no live account with that username (nothing changed);</li>
 *   <li>{@code 2}: usage error (admin mode without exactly one {@code --remove-account});</li>
 *   <li>{@code 1}: startup failed (database unreachable, schema doesn't validate, ...), from Spring Boot.</li>
 * </ul>
 * {@code --remove-account} without the admin profile refuses startup, so the live app can never be asked to do it
 * by accident. The admin process is separate from the live app, so it can't close the user's open sockets there.
 */
@Component
public class RemoveAccountRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RemoveAccountRunner.class);
    static final String OPTION = "remove-account";
    static final int REMOVED = 0;
    static final int USAGE = 2;
    static final int NOT_FOUND = 3;

    private final AccountRemovalService removal;
    private final ConfigurableApplicationContext context;
    private final Environment environment;

    public RemoveAccountRunner(AccountRemovalService removal, ConfigurableApplicationContext context,
                               Environment environment) {
        this.removal = removal;
        this.context = context;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean admin = environment.acceptsProfiles(Profiles.of("admin"));
        List<String> names = args.getOptionValues(OPTION);
        if (!admin) {
            if (names != null) {
                throw new IllegalStateException("--" + OPTION + " needs the admin profile (runbook 9a)");
            }
            return;
        }
        int code = exitCode(names);
        log.info("Admin mode finished with exit code {}", code);
        System.exit(SpringApplication.exit(context, () -> code));
    }

    int exitCode(List<String> names) {
        if (names == null || names.size() != 1 || names.getFirst().isBlank()) {
            log.error("Admin mode needs exactly one --{}=<username>", OPTION);
            return USAGE;
        }
        try {
            removal.removeAccount(names.getFirst());
            return REMOVED;
        } catch (IllegalArgumentException e) {
            log.error("Not removed: {} ({})", names.getFirst(), e.getMessage());
            return NOT_FOUND;
        }
    }
}
