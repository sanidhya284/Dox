package com.chatplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class ChatPlatformApplication {

    public static void main(String[] args) {
        if (!adminArgumentsAllowed(args, System.getenv("SPRING_PROFILES_ACTIVE"),
                System.getProperty("spring.profiles.active"))) {
            // Refuse before Spring starts: no migration, no seeding, no web server (runbook 9a).
            System.err.println("--remove-account needs the admin profile (SPRING_PROFILES_ACTIVE=prod,admin)");
            System.exit(2);
        }
        SpringApplication.run(ChatPlatformApplication.class, args);
    }

    /** {@code --remove-account} is only accepted together with the admin profile. */
    static boolean adminArgumentsAllowed(String[] args, String envProfiles, String propertyProfiles) {
        boolean wantsRemoval = Arrays.stream(args).anyMatch(a -> a.startsWith("--remove-account"));
        String profiles = (envProfiles == null ? "" : envProfiles) + "," + (propertyProfiles == null ? "" : propertyProfiles);
        boolean admin = Arrays.stream(profiles.split(",")).map(String::trim).anyMatch("admin"::equals);
        return !wantsRemoval || admin;
    }
}
