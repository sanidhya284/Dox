package com.chatplatform.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * Service for creating and validating JWT access tokens.
 *
 * Stateless. The secret is provided by configuration.
 */
@Service
public class JwtService {

    private static final String REJECTED_LEGACY_DEFAULT = "default-secret-key-for-development-only-must-be-long-enough";

    private final SecretKey key;

    public JwtService(@Value("${jwt.secret:}") String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("jwt.secret is not set. Set the JWT_SECRET environment variable, or activate the 'dev' profile for local development.");
        }
        if (secret.equals(REJECTED_LEGACY_DEFAULT)) {
            throw new IllegalStateException("JWT secret is the insecure development default. Set the JWT_SECRET environment variable before starting in production.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("jwt.secret must be at least 32 bytes long.");
        }
        
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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
