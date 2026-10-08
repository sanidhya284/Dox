package com.chatplatform.user.dto;

import com.chatplatform.user.repository.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Public representation of a user.
 * Never includes the password hash.
 */
public record UserResponse(
        UUID id,
        String username,
        String email,
        String displayName,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.id(),
                user.username(),
                user.email(),
                user.displayName(),
                user.createdAt()
        );
    }
}
