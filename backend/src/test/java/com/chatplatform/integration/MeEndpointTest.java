package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.guest.AccountRemovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/users/me (ADR-0012): who am I, for the UI (login and refresh return only a token). A new user with no
 * conversation still gets their name; a guest is labelled; a removed account gets 401 like its other calls.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class MeEndpointTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRemovalService removal;

    private final ObjectMapper mapper = new ObjectMapper();

    private ResponseEntity<String> me(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return restTemplate.exchange("/api/users/me", HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    @Test
    void aNewUserWithNoConversationGetsTheirOwnProfile() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        AuthResponse reg = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("me" + s, "me" + s + "@example.com", "password123", "Me " + s), AuthResponse.class)
                .getBody();
        ResponseEntity<String> r = me(reg.accessToken());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(r.getBody());
        assertThat(body.get("id").asText()).isEqualTo(reg.user().id().toString());
        assertThat(body.get("username").asText()).isEqualTo("me" + s);
        assertThat(body.get("displayName").asText()).isEqualTo("Me " + s);
        assertThat(body.get("isGuest").asBoolean()).isFalse();
        assertThat(body.has("passwordHash")).isFalse();
    }

    @Test
    void aGuestIsLabelled() throws Exception {
        ResponseEntity<String> g = restTemplate.postForEntity("/api/auth/guest",
                AuthCookies.request(null, AuthCookies.ORIGIN), String.class);
        String token = mapper.readTree(g.getBody()).get("accessToken").asText();
        assertThat(mapper.readTree(me(token).getBody()).get("isGuest").asBoolean()).isTrue();
    }

    @Test
    void noTokenIs401AndARemovedAccountIs401() {
        assertThat(me(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String s = UUID.randomUUID().toString().substring(0, 8);
        AuthResponse reg = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("mr" + s, "mr" + s + "@example.com", "password123", "X"), AuthResponse.class)
                .getBody();
        removal.removeAccount("mr" + s);
        assertThat(me(reg.accessToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
