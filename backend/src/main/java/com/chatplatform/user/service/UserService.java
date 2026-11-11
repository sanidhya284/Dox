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
                .orElseGet(() -> userRepository.save(User.newAccount(username, email, passwordHash, displayName)));
    }

    /** The welcome bot (ADR-0012): created once, never able to log in (its hash is not a BCrypt hash). */
    public User ensureBot(String username, String displayName) {
        return userRepository.findByUsername(username).orElseGet(() -> userRepository.save(new User(
                null, username, username + "@bot.invalid", NO_LOGIN_HASH, displayName, null, null, false, true,
                null)));
    }

    public java.util.Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * A "Try the demo" guest (ADR-0012): {@code guest-<10 hex>}, no usable password, labelled everywhere by
     * {@code isGuest}. The reserved "guest-" prefix can't be registered, so the name never collides with a
     * real account (a collision between two guests is a unique-key error, astronomically unlikely).
     */
    public User createGuest() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return userRepository.save(new User(null, "guest-" + hex.substring(0, 10), "guest-" + hex + "@guest.invalid",
                NO_LOGIN_HASH, "Guest " + hex.substring(0, 4).toUpperCase(java.util.Locale.ROOT), null, null,
                true, false, null));
    }

    /**
     * Blocks another user (ADR-0012): idempotent; yourself is 400; an unknown or removed account is 404.
     * The blocked user is not told: DM creation answers 404 as for an unknown user, search hides the blocker.
     */
    public void block(UUID me, UUID other) {
        if (me.equals(other)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "You can't block yourself");
        }
        if (getUserById(other).deletedAt() != null) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }
        userRepository.block(me, other);
    }

    public void unblock(UUID me, UUID other) {
        userRepository.unblock(me, other);
    }

    public java.util.List<com.chatplatform.user.dto.UserSearchResult> blocked(UUID me) {
        return userRepository.blockedBy(me);
    }

    public boolean blockedEitherWay(UUID a, UUID b) {
        return userRepository.blockedEitherWay(a, b);
    }

    /** For sends in a DM: the peer was removed, or either side blocked the other (ADR-0012). */
    public boolean directPeerUnavailable(UUID sender, UUID peer) {
        return userRepository.directPeerUnavailable(sender, peer);
    }

    /** What the send path needs about its caller, in one query (ADR-0012). */
    public record CallerStatus(boolean guest) {
    }

    /**
     * The caller's account must still exist and not be removed (ADR-0012 Decision 9). An access token outlives a
     * removal by up to 15 minutes; this turns it into 401 TOKEN_INVALID wherever something new would start.
     */
    public CallerStatus requireActive(UUID id) {
        boolean[] gd = userRepository.guestAndDeleted(id)
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID, "Account no longer exists"));
        if (gd[1]) {
            throw new ApiException(ErrorCode.TOKEN_INVALID, "Account was removed");
        }
        return new CallerStatus(gd[0]);
    }

    public boolean isGuest(UUID id) {
        return userRepository.isGuest(id);
    }

    public long countLiveGuests() {
        return userRepository.countLiveGuests();
    }

    /** See {@link UserRepository#lockNextExpiredGuest}; must run inside the caller's transaction. */
    public java.util.Optional<UUID> lockNextExpiredGuest(java.time.Instant cutoff) {
        return userRepository.lockNextExpiredGuest(cutoff);
    }

    public java.util.Optional<UUID> lockLiveByUsername(String username) {
        return userRepository.lockLiveByUsername(username);
    }

    public void anonymize(UUID id, String displayName) {
        userRepository.anonymize(id, displayName);
    }

    /** Not a BCrypt hash: BCrypt never matches it. Accounts with it can't log in by password. */
    static final String NO_LOGIN_HASH = "!no-password-login";

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
