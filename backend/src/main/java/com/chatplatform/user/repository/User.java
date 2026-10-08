package com.chatplatform.user.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal domain representation of a User.
 * Not exposed directly to the API (use UserResponse instead).
 */
public record User(
        UUID id,
        String username,
        String email,
        String passwordHash,
        String displayName,
        Instant createdAt,
        Instant updatedAt
) {}
