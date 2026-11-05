package com.chatplatform.shared.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Metrics are never public (ADR-0009). Every /actuator endpoint except health goes through this
 * chain, ordered before the JWT chain: HTTP Basic for a single operator user "metrics" with role
 * METRICS. The user exists only inside this chain's own authentication manager (not a global bean),
 * so its credentials do nothing on the API, which accepts JWT only.
 *
 * <p>Fails closed: if {@code metrics.password} (env METRICS_PASSWORD) is missing or shorter than 16
 * characters, no metrics user exists and every scrape gets 401. Basic auth sends the password with
 * every request, so production needs TLS in front (Phase 9).
 */
@Configuration
public class MetricsSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsSecurityConfig.class);
    static final String METRICS_USER = "metrics";
    private static final int MIN_PASSWORD_LENGTH = 16;

    @Bean
    @Order(1)
    public SecurityFilterChain metricsSecurityFilterChain(
            HttpSecurity http, @Value("${metrics.password:}") String metricsPassword) throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10); // checked on each scrape
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
        if (metricsPassword != null && metricsPassword.length() >= MIN_PASSWORD_LENGTH) {
            users.createUser(User.withUsername(METRICS_USER).password(encoder.encode(metricsPassword))
                    .roles("METRICS").build());
        } else {
            log.warn("Metrics scraping is DISABLED: set METRICS_PASSWORD (at least {} characters) to enable "
                    + "/actuator/prometheus for the '{}' user.", MIN_PASSWORD_LENGTH, METRICS_USER);
        }
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);

        http
            .securityMatcher(request -> {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                return path.startsWith("/actuator/")
                        && !path.equals("/actuator/health") && !path.startsWith("/actuator/health/");
            })
            .authenticationManager(new ProviderManager(provider))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(basic -> basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("METRICS"));
        return http.build();
    }
}
