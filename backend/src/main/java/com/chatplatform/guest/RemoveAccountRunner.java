package com.chatplatform.guest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runbook "remove an abusive account" (ADR-0012): {@code --remove-account=<username>} removes that account with
 * the same step as the guest cleanup, then exits (0 on success, 1 if there is no such live account).
 * Without the option it does nothing.
 */
@Component
public class RemoveAccountRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RemoveAccountRunner.class);
    static final String OPTION = "remove-account";

    private final AccountRemovalService removal;
    private final ConfigurableApplicationContext context;

    public RemoveAccountRunner(AccountRemovalService removal, ConfigurableApplicationContext context) {
        this.removal = removal;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> names = args.getOptionValues(OPTION);
        if (names == null) {
            return;
        }
        int code = 0;
        for (String name : names) {
            try {
                removal.removeAccount(name);
            } catch (IllegalArgumentException e) {
                log.error("Not removed: {} ({})", name, e.getMessage());
                code = 1;
            }
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }
}
