package com.chatplatform.auth.service;

import com.chatplatform.auth.dto.Registration;
import com.chatplatform.auth.dto.LoginRequest;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.auth.dto.TokenPair;
import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import com.chatplatform.user.dto.UserResponse;
import com.chatplatform.user.repository.User;
import com.chatplatform.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    private final RegistrationPolicy registrationPolicy;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService,
                       RegistrationPolicy registrationPolicy) {
        this.registrationPolicy = registrationPolicy;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        // A real BCrypt hash (same cost) of a random value nobody knows: compared against when there is no
        // login candidate, so refusing an unknown name costs the same as refusing a wrong password.
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    private final String dummyHash;

    @Transactional
    public Registration register(RegisterRequest request) {
        // First: without the invite code, nobody learns whether a username or email exists (ADR-0011).
        rejectReserved(request.username());
        registrationPolicy.check(request.inviteCode());
        if (userRepository.existsByUsername(request.username())) {
            throw new ApiException(ErrorCode.USERNAME_TAKEN);
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }

        String passwordHash = passwordEncoder.encode(request.password());

        User user = User.newAccount(request.username(), request.email(), passwordHash, request.displayName());

        User savedUser;
        try {
            savedUser = userRepository.save(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            String m = String.valueOf(e.getMostSpecificCause().getMessage());
            throw new ApiException(m.contains("uq_users_email") ? ErrorCode.EMAIL_TAKEN : ErrorCode.USERNAME_TAKEN);
        }
        TokenPair tokenPair = tokenService.generateTokenPair(savedUser.id());

        return new Registration(UserResponse.from(savedUser), tokenPair);
    }

    @Transactional
    public TokenPair login(LoginRequest request) {
        // Guests, the bot and removed accounts are not candidates: to login they are unknown usernames (ADR-0012).
        // Every refusal runs exactly one BCrypt comparison, so timing doesn't reveal which case it was.
        java.util.Optional<User> user = userRepository.findLoginCandidate(request.username());
        boolean ok = passwordEncoder.matches(request.password(), user.map(User::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !ok) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return tokenService.generateTokenPair(user.get().id());
    }


    /** Prefixes and names the system uses for its own accounts (ADR-0012); checked case-insensitively. */
    static final java.util.List<String> RESERVED_PREFIXES = java.util.List.of("guest-", "deleted-");
    public static final String BOT_USERNAME = "dox-bot";

    private static void rejectReserved(String username) {
        String u = username == null ? "" : username.toLowerCase(java.util.Locale.ROOT);
        if (u.equals(BOT_USERNAME) || RESERVED_PREFIXES.stream().anyMatch(u::startsWith)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "This username is reserved");
        }
    }
    
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPair refresh(String refreshToken) {
        return tokenService.refreshTokens(refreshToken);
    }
    
    @Transactional
    public void logout(String refreshToken) {
        tokenService.revokeToken(refreshToken);
    }
}
