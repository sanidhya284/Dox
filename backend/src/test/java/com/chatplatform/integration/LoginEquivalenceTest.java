package com.chatplatform.integration;

import com.chatplatform.guest.AccountRemovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0012: login can't tell an unknown username from a guest, the bot or a removed account. Each runs exactly
 * one BCrypt comparison against the same dummy hash and gets the same 401 body. (Same context as RateLimitTest,
 * which also spies on the PasswordEncoder.)
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LoginEquivalenceTest extends HardeningTestSupport {

    @SpyBean
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccountRemovalService removal;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unknownGuestBotAndDeletedUsersAllTakeTheSameDummyBcryptPath() throws Exception {
        ResponseEntity<String> guest = http().postForEntity("/api/auth/guest",
                new HttpEntity<>(null, withOrigin(from(uniqueIp()))), String.class);
        assertThat(guest.getStatusCode()).as(guest.getBody()).isEqualTo(HttpStatus.CREATED);
        String guestName = mapper.readTree(guest.getBody()).get("user").get("username").asText();
        String removed = newUser()[0];
        removal.removeAccount(removed);

        List<String> names = List.of("nobody-" + suffix(), guestName, "dox-bot", removed);
        List<String> bodies = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (String name : names) {
            Mockito.clearInvocations(passwordEncoder);
            ResponseEntity<String> r = login(name, PASSWORD, uniqueIp());
            assertThat(r.getStatusCode()).as(name).isEqualTo(HttpStatus.UNAUTHORIZED);
            bodies.add(mapper.readTree(r.getBody()).get("code").asText() + "|"
                    + mapper.readTree(r.getBody()).get("message").asText());
            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
            Mockito.verify(passwordEncoder, Mockito.times(1)).matches(Mockito.any(), hash.capture());
            hashes.add(hash.getValue());
        }
        assertThat(bodies).as("same error for all").containsOnly(bodies.getFirst());
        assertThat(hashes).as("same dummy hash for all").containsOnly(hashes.getFirst());
    }

    private static org.springframework.http.HttpHeaders withOrigin(org.springframework.http.HttpHeaders h) {
        h.setOrigin(AuthCookies.ORIGIN);
        return h;
    }
}
