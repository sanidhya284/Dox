package com.chatplatform.demo;

import com.chatplatform.conversation.dto.CreateGroupRequest;
import com.chatplatform.conversation.service.ConversationService;
import com.chatplatform.user.repository.User;
import com.chatplatform.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds the public demo (ADR-0011) when DEMO_PASSWORD is set: users demo1..demo3 (one password) and one
 * group, "Demo room", owned by demo1. Idempotent. A changed DEMO_PASSWORD replaces the demo users' password
 * hashes on the next start (their existing sessions keep working until they expire or are refreshed away).
 * Everything in the demo is public data: anyone with the password can read it.
 */
@Component
@org.springframework.context.annotation.Profile("!admin") // admin mode seeds nothing (runbook 9a)
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    public static final List<String> USERS = List.of("demo1", "demo2", "demo3");
    static final String ROOM = "Demo room";

    private final UserService userService;
    private final ConversationService conversationService;
    private final PasswordEncoder passwordEncoder;
    private final String configuredPassword;

    public DemoSeeder(UserService userService, ConversationService conversationService,
                      PasswordEncoder passwordEncoder, @Value("${demo.password:}") String configuredPassword) {
        this.userService = userService;
        this.conversationService = conversationService;
        this.passwordEncoder = passwordEncoder;
        this.configuredPassword = configuredPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (configuredPassword == null || configuredPassword.isBlank()) {
            return;
        }
        seed(configuredPassword);
        log.info("Demo accounts {} and '{}' are ready (public demo data only)", USERS, ROOM);
    }

    @Transactional
    public void seed(String password) {
        if (password.length() < 8) {
            throw new IllegalStateException("DEMO_PASSWORD must be at least 8 characters");
        }
        String hash = passwordEncoder.encode(password);
        List<User> users = USERS.stream()
                .map(u -> userService.createOrResetPassword(u, u + "@demo.invalid", u, hash))
                .toList();
        User owner = users.getFirst();
        boolean roomExists = conversationService.getUserConversations(owner.id()).stream()
                .anyMatch(c -> "GROUP".equalsIgnoreCase(c.type()) && ROOM.equals(c.title()));
        if (!roomExists) {
            conversationService.createGroup(owner.id(), new CreateGroupRequest(ROOM,
                    users.subList(1, users.size()).stream().map(User::id).toList()));
        }
    }
}
