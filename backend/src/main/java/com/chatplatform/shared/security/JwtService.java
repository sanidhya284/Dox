package com.chatplatform.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * Service for creating and validating JWT access tokens.
 *
 * Stateless. The secret is provided by configuration.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String INSECURE_DEFAULT = "default-secret-key-for-development-only-must-be-long-enough";
    /** Profiles in which the insecure dev default is explicitly permitted. */
    private static final Set<String> DEV_PROFILES = Set.of("default", "dev", "integration", "test");

    private final SecretKey key;
    private final String rawSecret;
    private final Environment environment;

    public JwtService(
            @Value("${jwt.secret:" + INSECURE_DEFAULT + "}") String secret,
            Environment environment) {
        this.rawSecret = secret;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.environment = environment;
    }

    @PostConstruct
    void validateSecret() {
        if (!rawSecret.equals(INSECURE_DEFAULT)) return; // custom secret provided — OK
        boolean isDevProfile = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(DEV_PROFILES::contains);
        if (!isDevProfile && environment.getActiveProfiles().length == 0) {
            // No active profiles AND using the default — treat as dev, warn loudly.
            log.warn("⚠️  JWT secret is the insecure development default. Set JWT_SECRET in production.");
            return;
        }
        if (!isDevProfile) {
            throw new IllegalStateException(
                    "JWT secret is the insecure development default. " +
                    "Set the JWT_SECRET environment variable before starting in production.");
        }
        log.warn("⚠️  JWT secret is the insecure development default (profile: {}). " +
                "This is acceptable for local dev only.", Arrays.toString(environment.getActiveProfiles()));
    }

    public String generateAccessToken(UUID userId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + SecurityConstants.ACCESS_TOKEN_EXPIRATION_MS))
                .signWith(key)
                .compact();
    }

    public UUID validateAndGetUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return UUID.fromString(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
