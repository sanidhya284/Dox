package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RefreshRequest;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.auth.dto.TokenPair;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenTheftTest extends AbstractIntegrationTest {

    @Test
    void testTokenRotationTheftDetection() {
        // Setup: user logs in (registers for simplicity here)
        RegisterRequest req = new RegisterRequest("thief_" + UUID.randomUUID(), "thief_" + UUID.randomUUID() + "@example.com", "password", "Thief");
        ResponseEntity<AuthResponse> res1 = restTemplate.postForEntity("/api/auth/register", req, AuthResponse.class);
        
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        
        String refreshToken1 = res1.getBody().refreshToken();
        
        // Action: legitimate client uses refreshToken1 -> gets refreshToken2 (refreshToken1 revoked)
        RefreshRequest refreshReq1 = new RefreshRequest(refreshToken1);
        ResponseEntity<TokenPair> res2 = restTemplate.postForEntity("/api/auth/refresh", refreshReq1, TokenPair.class);
        
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        String refreshToken2 = res2.getBody().refreshToken();
        
        // Action: attacker attempts to use refreshToken1 again (the "stolen" token)
        ResponseEntity<TokenPair> res3 = restTemplate.postForEntity("/api/auth/refresh", refreshReq1, TokenPair.class);
        
        // Assert: 401 on the replay
        assertThat(res3.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        
        // Assert: ALL tokens in the family are revoked, meaning refreshToken2 should now be invalid
        RefreshRequest refreshReq2 = new RefreshRequest(refreshToken2);
        ResponseEntity<TokenPair> res4 = restTemplate.postForEntity("/api/auth/refresh", refreshReq2, TokenPair.class);
        
        // The legitimate client's new token is also now invalid
        assertThat(res4.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
