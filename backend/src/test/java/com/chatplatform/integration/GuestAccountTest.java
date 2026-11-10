package com.chatplatform.integration;

import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.demo.DemoSeeder;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0012: "Try the demo" guest accounts. A guest is logged in through the normal refresh cookie, gets a welcome
 * DM from the bot and a private group with the bot, and is restricted (no password login, no groups).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class GuestAccountTest extends AbstractIntegrationTest {

    @Autowired
    private DemoSeeder demoSeeder;

    private final ObjectMapper mapper = new ObjectMapper();

    private ResponseEntity<String> createGuest() {
        return restTemplate.postForEntity("/api/auth/guest", AuthCookies.request(null, AuthCookies.ORIGIN), String.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private JsonNode conversations(String token) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange("/api/conversations", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(r.getBody());
    }

    private static boolean hasBotParticipant(JsonNode conv) {
        for (JsonNode p : conv.get("participants")) {
            if (p.get("isBot").asBoolean()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aGuestIsLoggedInWithACookieAndGetsAWelcomeDmAndAPrivateGroupWithTheBot() throws Exception {
        ResponseEntity<String> r = createGuest();
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(AuthCookies.setCookie(r)).contains("HttpOnly").contains("Path=/api/auth");
        JsonNode body = mapper.readTree(r.getBody());
        assertThat(body.get("user").get("isGuest").asBoolean()).isTrue();
        assertThat(body.get("user").get("username").asText()).startsWith("guest-");
        assertThat(body.toString()).doesNotContain("refreshToken");

        JsonNode convs = conversations(body.get("accessToken").asText());
        assertThat(convs).hasSize(2);
        JsonNode dm = null;
        JsonNode group = null;
        for (JsonNode c : convs) {
            if ("DIRECT".equals(c.get("type").asText())) {
                dm = c;
            } else {
                group = c;
            }
        }
        assertThat(dm).as("welcome DM").isNotNull();
        assertThat(hasBotParticipant(dm)).isTrue();
        assertThat(dm.get("lastMessage").get("preview").asText()).isNotBlank();
        assertThat(group).as("private group").isNotNull();
        assertThat(hasBotParticipant(group)).isTrue();
        assertThat(group.get("participants")).as("only the guest and the bot").hasSize(2);
    }

    @Test
    void twentyFiveGuestsInARowAllGetTheirWelcomeDmAndDemo1IsUntouched() throws Exception {
        demoSeeder.seed("demo-password-for-guest-test");
        ResponseEntity<String> login = restTemplate.postForEntity("/api/auth/login",
                Map.of("username", "demo1", "password", "demo-password-for-guest-test"), String.class);
        String demo1 = mapper.readTree(login.getBody()).get("accessToken").asText();
        String before = conversations(demo1).toString();

        for (int i = 0; i < 25; i++) {
            ResponseEntity<String> r = createGuest();
            assertThat(r.getStatusCode()).as("guest " + i + ": " + r.getBody()).isEqualTo(HttpStatus.CREATED);
            JsonNode convs = conversations(mapper.readTree(r.getBody()).get("accessToken").asText());
            assertThat(convs.findValues("type").stream().map(JsonNode::asText).toList())
                    .as("guest " + i).contains("DIRECT");
        }
        assertThat(conversations(demo1).toString()).as("demo1's list is unchanged").isEqualTo(before);
    }

    @Test
    void guestsCannotLogInByPasswordAndCannotCreateGroups() throws Exception {
        JsonNode guest = mapper.readTree(createGuest().getBody());
        String username = guest.get("user").get("username").asText();
        for (String password : List.of("", "password123", "!guest")) {
            ResponseEntity<String> r = restTemplate.postForEntity("/api/auth/login",
                    Map.of("username", username, "password", password.isEmpty() ? "x" : password), String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        String s = UUID.randomUUID().toString().substring(0, 8);
        UUID other = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("gx" + s, "gx" + s + "@example.com", "password123", "GX"),
                com.chatplatform.auth.dto.AuthResponse.class).getBody().user().id();
        ResponseEntity<String> group = restTemplate.exchange("/api/conversations/group", HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "nope", "memberIds", List.of(other)),
                        bearer(guest.get("accessToken").asText())), String.class);
        assertThat(group.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(group.getBody()).contains("GUEST_NOT_ALLOWED");
    }

    @Test
    void creatingAGuestNeedsAnAllowedOrigin() {
        assertThat(restTemplate.postForEntity("/api/auth/guest", AuthCookies.request(null, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.postForEntity("/api/auth/guest", AuthCookies.request(null, "https://evil.example"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void reservedUsernamesCannotBeRegistered() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        for (String name : List.of("guest-" + s, "GUEST-" + s, "deleted-" + s, "Deleted-" + s, "dox-bot", "Dox-Bot")) {
            ResponseEntity<String> r = restTemplate.postForEntity("/api/auth/register",
                    new RegisterRequest(name, name + s + "@example.com", "password123", "X"), String.class);
            assertThat(r.getStatusCode()).as(name).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody()).as(name).contains("reserved");
        }
    }
}
