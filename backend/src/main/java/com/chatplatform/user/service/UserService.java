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

    static final int SEARCH_MIN_CHARS = 2;
    static final int SEARCH_MAX_CHARS = 50;
    static final int SEARCH_LIMIT = 20;

    /**
     * Username prefix search for starting DMs (ADR-0012): case-insensitive, at least 2 characters, at most 20
     * results; LIKE wildcards in the query are matched literally. Never returns email.
     */
    public java.util.List<com.chatplatform.user.dto.UserSearchResult> search(UUID me, String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < SEARCH_MIN_CHARS || q.length() > SEARCH_MAX_CHARS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Search needs " + SEARCH_MIN_CHARS + " to " + SEARCH_MAX_CHARS + " characters");
        }
        String escaped = q.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return userRepository.search(escaped + "%", me, SEARCH_LIMIT);
    }

    /** Public summaries for rendering names; one query for any number of ids. */
    public java.util.Map<UUID, com.chatplatform.user.dto.UserSummary> summaries(java.util.Collection<UUID> ids) {
        java.util.Map<UUID, com.chatplatform.user.dto.UserSummary> out = new java.util.HashMap<>();
        for (com.chatplatform.user.dto.UserSummary s : userRepository.summaries(ids)) {
            out.put(s.id(), s);
        }
        return out;
    }

    public User getUserById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    // Methods for registration are handled by AuthService calling UserRepository directly,
    // or AuthService could call UserService. Since they are closely related and
    // UserRepository provides the precise primitives, AuthService can use UserRepository.
}
