package com.chatplatform.user.service;

import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import com.chatplatform.user.repository.User;
import com.chatplatform.user.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Demo seeding only (ADR-0011): creates the user, or replaces an existing user's password hash, so a
     * changed DEMO_PASSWORD takes effect on the next start. Existing sessions are not revoked.
     */
    public User createOrResetPassword(String username, String email, String displayName, String passwordHash) {
        return userRepository.findByUsername(username)
                .map(existing -> {
                    userRepository.updatePasswordHash(existing.id(), passwordHash);
                    return existing;
                })
                .orElseGet(() -> userRepository.save(
                        new User(null, username, email, passwordHash, displayName, null, null)));
    }

    public User getUserById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    // Methods for registration are handled by AuthService calling UserRepository directly,
    // or AuthService could call UserService. Since they are closely related and
    // UserRepository provides the precise primitives, AuthService can use UserRepository.
}
