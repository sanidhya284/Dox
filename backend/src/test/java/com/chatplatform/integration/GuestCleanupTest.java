package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.guest.AccountRemovalService;
import com.chatplatform.realtime.service.WsTicketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ADR-0012: guests expire after 24 h. Conversations whose other members are only system accounts (bot, demo)
 * or guests are deleted; where a real user took part, the guest leaves and is anonymized, and the conversation
 * keeps every message with contiguous seq. Sessions end: refresh tokens revoked, sockets closed with 4008.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class GuestCleanupTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRemovalService removal;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WsTicketService wsTicketService;

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();

    /** [guestId, accessToken, refreshToken] */
    private String[] newGuest() throws Exception {
        ResponseEntity<String> r = restTemplate.postForEntity("/api/auth/guest",
                AuthCookies.request(null, AuthCookies.ORIGIN), String.class);
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = mapper.readTree(r.getBody());
        return new String[]{body.get("user").get("id").asText(), body.get("accessToken").asText(),
                AuthCookies.refreshToken(r)};
    }

    private void age(String userId) {
        jdbc.sql("UPDATE users SET created_at = now() - interval '25 hours' WHERE id = :id")
                .param("id", UUID.fromString(userId)).update();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private JsonNode get(String token, String path) throws Exception {
        ResponseEntity<String> r = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)),
                String.class);
        assertThat(r.getStatusCode()).as(path + " " + r.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(r.getBody());
    }

    private void send(String token, String conversationId, String body) {
        ResponseEntity<String> r = restTemplate.exchange("/api/conversations/" + conversationId + "/messages",
                HttpMethod.POST, new HttpEntity<>(Map.of("clientMsgId", UUID.randomUUID(), "body", body),
                        bearer(token)), String.class);
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    private List<UUID> conversationIdsOf(String token) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode c : get(token, "/api/conversations")) {
            ids.add(UUID.fromString(c.get("id").asText()));
        }
        return ids;
    }

    private long count(String sql, List<UUID> ids) {
        return jdbc.sql(sql).param("ids", ids).query(Long.class).single();
    }

    @Test
    void seededConversationsAreDeletedAndTheGuestIsAnonymizedAndLoggedOut() throws Exception {
        String[] guest = newGuest();
        List<UUID> seeded = conversationIdsOf(guest[1]);
        assertThat(seeded).hasSize(2);
        send(guest[1], seeded.getFirst().toString(), "hello bot");
        WsTestClient socket = WsTestClient.connect(port, wsTicketService.issue(UUID.fromString(guest[0])).ticket());
        age(guest[0]);

        removal.removeExpiredGuests();

        assertThat(count("SELECT count(*) FROM conversations WHERE id IN (:ids)", seeded)).isZero();
        assertThat(count("SELECT count(*) FROM messages WHERE conversation_id IN (:ids)", seeded)).isZero();
        Map<String, Object> row = jdbc.sql("SELECT username::text AS u, email::text AS e, display_name, deleted_at "
                        + "FROM users WHERE id = :id")
                .param("id", UUID.fromString(guest[0])).query().singleRow();
        assertThat((String) row.get("u")).matches("deleted-[0-9a-f]{32}");
        assertThat((String) row.get("e")).endsWith("@deleted.invalid");
        assertThat(row.get("display_name")).isEqualTo("Deleted guest");
        assertThat(row.get("deleted_at")).isNotNull();

        assertThat(AuthCookies.refresh(restTemplate, guest[2]).getStatusCode())
                .as("refresh token revoked").isEqualTo(HttpStatus.UNAUTHORIZED);
        await("the guest's socket to be closed with 4008").atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(socket.closed).isDone();
                    assertThat(socket.closed.get().getCode()).isEqualTo(4008);
                });
    }

    @Test
    void whereARealUserTookPartTheGuestLeavesAndHistoryStaysContiguous() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        AuthResponse real = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("gc" + s, "gc" + s + "@example.com", "password123", "Real " + s),
                AuthResponse.class).getBody();
        String[] guest = newGuest();
        ResponseEntity<String> dm = restTemplate.exchange("/api/conversations/direct", HttpMethod.POST,
                new HttpEntity<>(Map.of("otherUserId", real.user().id()), bearer(guest[1])), String.class);
        assertThat(dm.getStatusCode()).as(dm.getBody()).isIn(HttpStatus.OK, HttpStatus.CREATED);
        String dmId = mapper.readTree(dm.getBody()).get("id").asText();
        send(real.accessToken(), dmId, "one");
        send(guest[1], dmId, "two");
        send(real.accessToken(), dmId, "three");
        age(guest[0]);

        removal.removeExpiredGuests();

        JsonNode page = get(real.accessToken(), "/api/conversations/" + dmId + "/messages?limit=10");
        List<Long> seqs = new ArrayList<>();
        page.get("messages").forEach(m -> seqs.add(m.get("seq").asLong()));
        assertThat(seqs).containsExactlyInAnyOrder(1L, 2L, 3L);
        JsonNode conv = get(real.accessToken(), "/api/conversations/" + dmId);
        JsonNode guestInfo = null;
        for (JsonNode p : conv.get("participants")) {
            if (p.get("id").asText().equals(guest[0])) {
                guestInfo = p;
            }
        }
        assertThat(guestInfo).as("the guest is still listed, so its messages keep a name").isNotNull();
        assertThat(guestInfo.get("isDeleted").asBoolean()).isTrue();
        assertThat(guestInfo.get("left").asBoolean()).isTrue();
        assertThat(guestInfo.get("displayName").asText()).isEqualTo("Deleted guest");
        assertThat(conversationIdsOf(guest[1])).as("the guest is in no conversation any more").isEmpty();
        ResponseEntity<String> toDeleted = restTemplate.exchange("/api/conversations/" + dmId + "/messages",
                HttpMethod.POST, new HttpEntity<>(Map.of("clientMsgId", UUID.randomUUID(), "body", "hello?"),
                        bearer(real.accessToken())), String.class);
        assertThat(toDeleted.getStatusCode()).as("a DM with a removed account").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(toDeleted.getBody()).contains("RECIPIENT_UNAVAILABLE");
    }

    @Test
    void aDmWithAnotherGuestWhoIsStillLiveSurvivesWithSeqIntact() throws Exception {
        String[] expiring = newGuest();
        String[] live = newGuest();
        ResponseEntity<String> dm = restTemplate.exchange("/api/conversations/direct", HttpMethod.POST,
                new HttpEntity<>(Map.of("otherUserId", expiring[0]), bearer(live[1])), String.class);
        assertThat(dm.getStatusCode()).as(dm.getBody()).isEqualTo(HttpStatus.CREATED);
        String dmId = mapper.readTree(dm.getBody()).get("id").asText();
        send(live[1], dmId, "one");
        send(expiring[1], dmId, "two");
        send(live[1], dmId, "three");
        age(expiring[0]);

        removal.removeExpiredGuests();

        assertThat(deletedAt(expiring[0])).isNotNull();
        assertThat(deletedAt(live[0])).isNull();
        JsonNode page = get(live[1], "/api/conversations/" + dmId + "/messages?after=0&limit=10");
        List<Long> seqs = new ArrayList<>();
        page.get("messages").forEach(m -> seqs.add(m.get("seq").asLong()));
        assertThat(seqs).as("the live guest keeps the whole DM").containsExactly(1L, 2L, 3L);
        JsonNode gone = null;
        for (JsonNode p : get(live[1], "/api/conversations/" + dmId).get("participants")) {
            if (p.get("id").asText().equals(expiring[0])) {
                gone = p;
            }
        }
        assertThat(gone.get("isDeleted").asBoolean()).isTrue();
        assertThat(gone.get("left").asBoolean()).isTrue();
    }

    @Test
    void aRowLockedByAnotherInstanceIsSkippedNotWaitedOn() throws Exception {
        String[] locked = newGuest();
        String[] free = newGuest();
        age(locked[0]);
        age(free[0]);
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, UUID.fromString(locked[0]));
                ps.executeQuery();
            }
            CompletableFuture.runAsync(() -> removal.removeExpiredGuests()).get(20, TimeUnit.SECONDS);
            other.rollback();
        }
        assertThat(deletedAt(free[0])).as("the unlocked guest was removed").isNotNull();
        assertThat(deletedAt(locked[0])).as("the locked guest was skipped").isNull();
        removal.removeExpiredGuests();
        assertThat(deletedAt(locked[0])).as("and removed on the next run").isNotNull();
    }

    @Test
    void aFreshGuestIsNotRemoved() throws Exception {
        String[] guest = newGuest();
        removal.removeExpiredGuests();
        assertThat(deletedAt(guest[0])).isNull();
        assertThat(conversationIdsOf(guest[1])).hasSize(2);
    }

    @Test
    void removingAnAbusiveAccountUsesTheSameAnonymizeStep() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<AuthResponse> reg = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("ab" + s, "ab" + s + "@example.com", "password123", "Abuser"),
                AuthResponse.class);
        UUID id = reg.getBody().user().id();

        removal.removeAccount("ab" + s);

        assertThat(deletedAt(id.toString())).isNotNull();
        assertThat(jdbc.sql("SELECT username::text FROM users WHERE id = :id").param("id", id)
                .query(String.class).single()).matches("deleted-[0-9a-f]{32}");
        assertThat(AuthCookies.refresh(restTemplate, AuthCookies.refreshToken(reg)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.postForEntity("/api/auth/login",
                Map.of("username", "ab" + s, "password", "password123"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private Object deletedAt(String userId) {
        return jdbc.sql("SELECT deleted_at FROM users WHERE id = :id").param("id", UUID.fromString(userId))
                .query().singleValue();
    }
}
