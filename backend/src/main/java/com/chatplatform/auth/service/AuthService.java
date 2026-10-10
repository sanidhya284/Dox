package com.chatplatform.auth.service;

import com.chatplatform.auth.dto.AuthResponse;
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

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ApiException(ErrorCode.USERNAME_TAKEN);
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }

        String passwordHash = passwordEncoder.encode(request.password());

        User user = new User(
                null,
                request.username(),
                request.email(),
                passwordHash,
                request.displayName(),
                null,
                null
        );

        User savedUser = userRepository.save(user);
        TokenPair tokenPair = tokenService.generateTokenPair(savedUser.id());

        return new AuthResponse(UserResponse.from(savedUser), tokenPair.accessToken(), tokenPair.refreshToken());
    }

    @Transactional
    public TokenPair login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        return tokenService.generateTokenPair(user.id());
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
