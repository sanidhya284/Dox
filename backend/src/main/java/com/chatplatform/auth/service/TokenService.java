package com.chatplatform.auth.service;

import com.chatplatform.auth.dto.TokenPair;
import com.chatplatform.auth.repository.RefreshToken;
import com.chatplatform.auth.repository.RefreshTokenRepository;
import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import com.chatplatform.shared.security.JwtService;
import com.chatplatform.shared.security.SecurityConstants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TokenService {

    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;

    public TokenService(JwtService jwtService, RefreshTokenRepository refreshTokenRepository) {
        this.jwtService = jwtService;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public TokenPair generateTokenPair(UUID userId) {
        return createTokenPair(userId, UUID.randomUUID());
    }

    @Transactional
    public TokenPair refreshTokens(String rawRefreshToken) {
        String tokenHash = hashToken(rawRefreshToken);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID, "Token not found"));

        if (storedToken.revokedAt() != null) {
            // THEFT DETECTION: this token was revoked but is being used again.
            // Revoke the entire family to protect the user.
            refreshTokenRepository.revokeFamily(storedToken.familyId());
            throw new ApiException(ErrorCode.TOKEN_INVALID, "Token was revoked");
        }

        if (storedToken.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.TOKEN_INVALID, "Token expired");
        }

        // Revoke the old token
        refreshTokenRepository.revoke(tokenHash);

        // Generate a new token pair in the same family
        return createTokenPair(storedToken.userId(), storedToken.familyId());
    }

    @Transactional
    public void revokeToken(String rawRefreshToken) {
        String tokenHash = hashToken(rawRefreshToken);
        refreshTokenRepository.revoke(tokenHash);
    }

    private TokenPair createTokenPair(UUID userId, UUID familyId) {
        String accessToken = jwtService.generateAccessToken(userId);
        
        String rawRefreshToken = UUID.randomUUID().toString();
        String tokenHash = hashToken(rawRefreshToken);
        
        RefreshToken refreshToken = new RefreshToken(
                null,
                userId,
                tokenHash,
                familyId,
                Instant.now().plusMillis(SecurityConstants.REFRESH_TOKEN_EXPIRATION_MS),
                null,
                null
        );
        refreshTokenRepository.insert(refreshToken);

        return new TokenPair(accessToken, rawRefreshToken);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
