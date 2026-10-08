package com.chatplatform.auth.repository;

import java.time.Instant;
import java.util.UUID;

public record RefreshToken(
        UUID id,
        UUID userId,
        String tokenHash,
        UUID familyId,
        Instant expiresAt,
        Instant revokedAt,
        Instant createdAt
) {
    public boolean isValid() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }
}
