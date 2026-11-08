package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenTheftTest extends AbstractIntegrationTest {

    @Test
    void testTokenRotationTheftDetection() {
        // Setup: user logs in (registers for simplicity here)
        // Username must be 3-50 chars, password must be 8+ chars
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        RegisterRequest req = new RegisterRequest(
                "thief_" + suffix,
                "thief_" + suffix + "@example.com",
                "password123",
                "Thief"
        );
        ResponseEntity<AuthResponse> res1 = restTemplate.postForEntity("/api/auth/register", req, AuthResponse.class);

        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res1.getBody()).isNotNull();

        // The refresh token is only in the HttpOnly cookie (ADR-0010).
        String refreshToken1 = AuthCookies.refreshToken(res1);

        // Action: legitimate client uses refreshToken1 -> gets refreshToken2 (refreshToken1 revoked)
        ResponseEntity<String> res2 = AuthCookies.refresh(restTemplate, refreshToken1);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.OK);
        String refreshToken2 = AuthCookies.refreshToken(res2);

        // Action: attacker attempts to use refreshToken1 again (the "stolen" token)
        ResponseEntity<String> res3 = AuthCookies.refresh(restTemplate, refreshToken1);

        // Assert: 401 on the replay, and the browser is told to drop the cookie
        assertThat(res3.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(AuthCookies.setCookie(res3)).contains("Max-Age=0");

        // Assert: ALL tokens in the family are revoked, meaning refreshToken2 should now be invalid
        ResponseEntity<String> res4 = AuthCookies.refresh(restTemplate, refreshToken2);

        // The legitimate client's new token is also now invalid
        assertThat(res4.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
