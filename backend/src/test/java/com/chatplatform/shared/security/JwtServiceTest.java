package com.chatplatform.shared.security;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    @Test
    void constructorRejectsNullSecret() {
        assertThatThrownBy(() -> new JwtService(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorRejectsBlankSecret() {
        assertThatThrownBy(() -> new JwtService("   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorRejectsOldInsecureDefault() {
        assertThatThrownBy(() -> new JwtService("default-secret-key-for-development-only-must-be-long-enough"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorRejectsSecretShorterThan32Bytes() {
        // 31 characters
        String shortSecret = "1234567890123456789012345678901";
        assertThatThrownBy(() -> new JwtService(shortSecret))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorAcceptsValidSecretAndCanGenerateAndParseToken() {
        // 40 bytes
        String validSecret = "1234567890123456789012345678901234567890";
        JwtService jwtService = new JwtService(validSecret);
        
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId);
        
        assertThat(token).isNotNull();
        UUID parsedUserId = jwtService.validateAndGetUserId(token);
        assertThat(parsedUserId).isEqualTo(userId);
    }
}
