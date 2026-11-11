package com.chatplatform.shared.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the PRODUCTION limit values (ADR-0011, ADR-0012). It binds exactly what the server runs with:
 * application.yml plus application-prod.yml, no test or e2e overrides (the tests and Playwright raise the limits,
 * so without this test a production value could change unnoticed). Changing a value here is a decision: update
 * ADR-0012 and architecture.md in the same commit.
 */
class RateLimitProductionValuesTest {

    private static Binder productionBinder() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        // Later profiles win: application-prod.yml is added first so it takes precedence over application.yml.
        for (PropertySource<?> s : loader.load("prod", new ClassPathResource("application-prod.yml"))) {
            sources.addLast(s);
        }
        for (PropertySource<?> s : loader.load("base", new ClassPathResource("application.yml"))) {
            sources.addLast(s);
        }
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }

    @Test
    void productionRateLimitsAreTheDocumentedValues() throws IOException {
        RateLimitProperties p = productionBinder().bind("ratelimit", Bindable.of(RateLimitProperties.class)).get();
        assertThat(p.registerPerIpPerHour()).isEqualTo(5);
        assertThat(p.signupGlobalPerHour()).isEqualTo(200);
        assertThat(p.loginPerIpPerMinute()).isEqualTo(20);
        assertThat(p.loginPerIpAndUserPerMinute()).isEqualTo(5);
        assertThat(p.ticketPerUserPerMinute()).isEqualTo(30);
        assertThat(p.failedHandshakesPerIpPerMinute()).isEqualTo(20);
        assertThat(p.searchPerUserPerMinute()).isEqualTo(30);
        assertThat(p.guestPerIpPerHour()).isEqualTo(3);
        assertThat(p.sendPerUserPerMinute()).isEqualTo(60);
        assertThat(p.sendPerGuestPerMinute()).isEqualTo(20);
        assertThat(p.dmPerUserPerHour()).isEqualTo(20);
        assertThat(p.dmPerGuestPerHour()).isEqualTo(3);
        assertThat(p.maxKeys()).isEqualTo(100_000);
    }

    @Test
    void productionGuestAndRegistrationSettingsAreTheDocumentedValues() throws IOException {
        Binder b = productionBinder();
        assertThat(b.bind("guest.max-live", Integer.class).get()).isEqualTo(1000);
        assertThat(b.bind("guest.ttl", Duration.class).get()).isEqualTo(Duration.ofHours(24));
        assertThat(b.bind("guest.cleanup-interval", Duration.class).get()).isEqualTo(Duration.ofMinutes(15));
        assertThat(b.bind("registration.mode", String.class).get()).as("open unless REGISTRATION_MODE says otherwise")
                .isEqualTo("open");
    }

    @Test
    void theCodeDefaultsMatchTheConfiguredValues() {
        // The @DefaultValue fallbacks (used if application.yml lost a key) must not be looser than production.
        RateLimitProperties defaults = new Binder(ConfigurationPropertySources.from(new MutablePropertySources()))
                .bindOrCreate("ratelimit", Bindable.of(RateLimitProperties.class));
        assertThat(defaults.sendPerUserPerMinute()).isEqualTo(60);
        assertThat(defaults.signupGlobalPerHour()).isEqualTo(200);
        assertThat(defaults.guestPerIpPerHour()).isEqualTo(3);
        assertThat(defaults.dmPerGuestPerHour()).isEqualTo(3);
    }
}
