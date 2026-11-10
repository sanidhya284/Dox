package com.chatplatform.user.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal domain representation of a User.
 * Not exposed directly to the API (use UserResponse instead).
 * {@code isGuest}, {@code isBot} and {@code deletedAt} are ADR-0012 (guest, bot and removed accounts).
 */
public record User(
        UUID id,
        String username,
        String email,
        String passwordHash,
        String displayName,
        Instant createdAt,
        Instant updatedAt,
        boolean isGuest,
        boolean isBot,
        Instant deletedAt
) {
    /** A normal account for registration: not a guest, not a bot. */
    public static User newAccount(String username, String email, String passwordHash, String displayName) {
        return new User(null, username, email, passwordHash, displayName, null, null, false, false, null);
    }
}
