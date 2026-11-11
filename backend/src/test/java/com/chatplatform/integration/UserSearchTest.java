package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.user.repository.UserRepository;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-0012: username prefix search for starting DMs. Never exposes email; bounded; excludes the wrong people. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class UserSearchTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private final ObjectMapper mapper = new ObjectMapper();

    private static String tag() {
        return "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
    }

    private AuthResponse register(String username) {
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(username, username + "@example.com", "password123", "Name " + username),
                AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private ResponseEntity<String> search(AuthResponse as, String q) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(as.accessToken());
        return restTemplate.exchange("/api/users/search?q=" + q, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private List<String> usernames(ResponseEntity<String> r) throws Exception {
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        List<String> out = new ArrayList<>();
        for (JsonNode n : mapper.readTree(r.getBody())) {
            out.add(n.get("username").asText());
        }
        return out;
    }

    @Test
    void findsByCaseInsensitivePrefixAndNeverReturnsEmail() throws Exception {
        String t = tag();
        AuthResponse me = register(t + "me");
        register(t + "alice");
        register(t + "bob");
        ResponseEntity<String> r = search(me, t.toUpperCase() + "A");
        assertThat(usernames(r)).containsExactly(t + "alice");
        assertThat(r.getBody()).doesNotContain("@example.com").doesNotContain("email");
        JsonNode first = mapper.readTree(r.getBody()).get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.get("displayName").asText()).isEqualTo("Name " + t + "alice");
        assertThat(first.get("isGuest").asBoolean()).isFalse();
    }

    @Test
    void queriesShorterThanTwoCharactersAreRejected() {
        AuthResponse me = register(tag() + "q");
        assertThat(search(me, "a").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // "+" in a query string decodes to a space on the server: two spaces, blank after trim.
        assertThat(search(me, "++").getStatusCode()).as("blank after trim").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void returnsAtMostTwentyResults() throws Exception {
        String t = tag();
        AuthResponse me = register(t + "zz");
        for (int i = 0; i < 22; i++) {
            register(t + "m" + i);
        }
        assertThat(usernames(search(me, t + "m"))).hasSize(20);
    }

    @Test
    void likeWildcardsInTheQueryAreLiteral() throws Exception {
        String t = tag();
        AuthResponse me = register(t + "zz");
        register(t + "_a");
        register(t + "xa");
        // Unescaped, "_" would match any single character and also return t + "xa".
        assertThat(usernames(search(me, t + "_"))).containsExactly(t + "_a");
        // TestRestTemplate encodes "%" as %25, so the server receives a real "%". Unescaped, "<t>%" would
        // match every user starting with t (three of them); escaped, it matches nothing.
        assertThat(usernames(search(me, t + "%"))).as("a literal percent sign matches nothing").isEmpty();
    }

    @Test
    void excludesMyselfDeletedAccountsBotsAndUsersWhoBlockedMe() throws Exception {
        String t = tag();
        AuthResponse me = register(t + "me");
        AuthResponse visible = register(t + "mvisible");
        AuthResponse deleted = register(t + "mdeleted");
        AuthResponse bot = register(t + "mbot");
        AuthResponse blocker = register(t + "mblocker");
        jdbc.sql("UPDATE users SET deleted_at = now() WHERE id = :id").param("id", deleted.user().id()).update();
        jdbc.sql("UPDATE users SET is_bot = true WHERE id = :id").param("id", bot.user().id()).update();
        jdbc.sql("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (:a, :b)")
                .param("a", blocker.user().id()).param("b", me.user().id()).update();

        assertThat(usernames(search(me, t + "m"))).containsExactly(t + "mvisible");
        assertThat(usernames(search(visible, t + "m")))
                .as("the blocker is hidden only from the user they blocked")
                .contains(t + "mblocker", t + "me");
    }

    /**
     * The search query can be served by the prefix index (lower(username) text_pattern_ops). Sequential and
     * bitmap scans are disabled in this one transaction because on a small shared table the planner may prefer
     * them; the assertion is "the index can serve this query", not a performance claim.
     */
    @Test
    void searchQueryUsesThePrefixIndex() {
        AuthResponse me = register(tag() + "x");
        List<String> plan = new TransactionTemplate(txManager).execute(st -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            jdbc.sql("SET LOCAL enable_bitmapscan = off").update();
            return jdbc.sql("EXPLAIN " + UserRepository.SEARCH_SQL)
                    .param("pattern", "ab%").param("me", me.user().id()).param("limit", 20)
                    .query(String.class).list();
        });
        String text = String.join("\n", plan);
        System.out.println("EXPLAIN (user search):\n" + text);
        assertThat(text).as(text).contains("idx_users_username_prefix");
    }
}
