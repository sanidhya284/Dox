package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.conversation.service.ConversationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Delivered/read cursors (ADR-0007): monotonic, guarded in the WHERE, membership checked first. */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
public class CursorTest extends AbstractIntegrationTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper mapper;

    private AuthResponse register(String prefix) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(prefix + s, prefix + s + "@example.com", "password123", "U " + s),
                AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private HttpHeaders bearer(AuthResponse u) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(u.accessToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** A DM whose last_seq is set to 100 directly, so cursors up to 100 are valid without 100 sends. */
    private UUID dmWithLastSeq100(AuthResponse a, AuthResponse b) {
        ResponseEntity<ConversationResponse> res = restTemplate.postForEntity("/api/conversations/direct",
                new HttpEntity<>(new CreateDirectRequest(b.user().id()), bearer(a)), ConversationResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID conv = res.getBody().id();
        jdbcClient.sql("UPDATE conversations SET last_seq = 100 WHERE id = :c").param("c", conv).update();
        return conv;
    }

    private UUID groupWithLastSeq100(AuthResponse owner, AuthResponse member) {
        ResponseEntity<ConversationResponse> res = restTemplate.postForEntity("/api/conversations/group",
                new HttpEntity<>(new com.chatplatform.conversation.dto.CreateGroupRequest("cc", List.of(member.user().id())),
                        bearer(owner)), ConversationResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID conv = res.getBody().id();
        jdbcClient.sql("UPDATE conversations SET last_seq = 100 WHERE id = :c").param("c", conv).update();
        return conv;
    }

    private ResponseEntity<String> cursor(AuthResponse u, UUID conv, String kind, long seq) {
        return restTemplate.postForEntity("/api/conversations/" + conv + "/cursor",
                new HttpEntity<>("{\"kind\":\"" + kind + "\",\"seq\":" + seq + "}", bearer(u)), String.class);
    }

    private long[] row(UUID conv, UUID user) {
        return jdbcClient.sql("SELECT last_delivered_seq, last_read_seq FROM conversation_participants "
                        + "WHERE conversation_id = :c AND user_id = :u")
                .param("c", conv).param("u", user)
                .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}).single();
    }

    private String xmin(UUID conv, UUID user) {
        return jdbcClient.sql("SELECT xmin::text FROM conversation_participants WHERE conversation_id = :c AND user_id = :u")
                .param("c", conv).param("u", user).query(String.class).single();
    }

    @Test
    void cursorsOnlyMoveForwardAndReadImpliesDelivered() throws Exception {
        AuthResponse a = register("cmA_");
        AuthResponse b = register("cmB_");
        UUID conv = dmWithLastSeq100(a, b);
        UUID me = a.user().id();

        ResponseEntity<String> r = cursor(a, conv, "read", 10);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(r.getBody());
        assertThat(body.get("lastReadSeq").asLong()).isEqualTo(10);
        assertThat(body.get("lastDeliveredSeq").asLong()).as("read implies delivered").isEqualTo(10);

        assertThat(cursor(a, conv, "read", 7).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(row(conv, me)).as("older read cursor ignored").containsExactly(10, 10);

        cursor(a, conv, "delivered", 12);
        assertThat(row(conv, me)).containsExactly(12, 10);

        cursor(a, conv, "read", 11);
        assertThat(row(conv, me)).as("read 11 does not lower delivered 12").containsExactly(12, 11);

        cursor(a, conv, "delivered", 5);
        assertThat(row(conv, me)).containsExactly(12, 11);
    }

    /**
     * A stale cursor matches no row (the guard is in the WHERE), so nothing is written. Checked via
     * xmin: any UPDATE that matches the row, even one writing the same values, creates a new row
     * version with a new xmin; an UPDATE whose WHERE filters the row out leaves xmin unchanged.
     */
    @Test
    void staleCursorWritesNothing() {
        AuthResponse a = register("csA_");
        AuthResponse b = register("csB_");
        UUID conv = dmWithLastSeq100(a, b);
        UUID me = a.user().id();
        cursor(a, conv, "read", 10);
        String before = xmin(conv, me);

        cursor(a, conv, "read", 7);
        cursor(a, conv, "read", 10);      // equal is not an advance either
        cursor(a, conv, "delivered", 3);
        cursor(a, conv, "delivered", 10);

        assertThat(xmin(conv, me)).as("row version unchanged by stale cursors").isEqualTo(before);
        assertThat(row(conv, me)).containsExactly(10, 10);
    }

    /**
     * 8 threads x 25 updates with random seqs (both kinds) per round; the final cursors must equal the
     * maxima submitted, whatever the interleaving. 20 rounds; failing rounds are counted, not hidden.
     */
    @Test
    void concurrentUpdatesNeverMoveACursorBackwards() throws Exception {
        int rounds = 20;
        int threads = 8;
        int perThread = 25;
        List<String> badRounds = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // Two users for all rounds (registration is slow: BCrypt); a fresh GROUP per round gives a
            // fresh participant row (direct conversations between the same two users are deduplicated).
            AuthResponse a = register("ccA_");
            AuthResponse b = register("ccB_");
            UUID me = a.user().id();
            for (int round = 1; round <= rounds; round++) {
                UUID conv = groupWithLastSeq100(a, b);
                Random rnd = new Random(round);
                long[][] seqs = new long[threads][perThread];
                boolean[][] isRead = new boolean[threads][perThread];
                long maxRead = 0;
                long maxAny = 0;
                for (int t = 0; t < threads; t++) {
                    for (int i = 0; i < perThread; i++) {
                        seqs[t][i] = 1 + rnd.nextInt(100);
                        isRead[t][i] = rnd.nextBoolean();
                        maxAny = Math.max(maxAny, seqs[t][i]);
                        if (isRead[t][i]) {
                            maxRead = Math.max(maxRead, seqs[t][i]);
                        }
                    }
                }
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int tt = t;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        for (int i = 0; i < perThread; i++) {
                            conversationService.advanceCursor(conv, me, isRead[tt][i] ? "read" : "delivered", seqs[tt][i]);
                        }
                        return null;
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                long[] got = row(conv, me);
                if (got[1] != maxRead || got[0] != maxAny) {
                    badRounds.add("round " + round + ": got delivered=" + got[0] + " read=" + got[1]
                            + ", expected delivered=" + maxAny + " read=" + maxRead);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("CURSOR_CONCURRENCY bad=" + badRounds.size() + "/" + rounds + " " + badRounds);
        assertThat(badRounds).as("rounds where a cursor ended below the maximum submitted").isEmpty();
    }

    @Test
    void invalidSeqOrKindIsRejectedAndChangesNothing() throws Exception {
        AuthResponse a = register("cvA_");
        AuthResponse b = register("cvB_");
        UUID conv = dmWithLastSeq100(a, b);
        cursor(a, conv, "read", 10);

        for (ResponseEntity<String> r : List.of(
                cursor(a, conv, "read", 101),        // beyond last_seq
                cursor(a, conv, "read", -1),
                cursor(a, conv, "seen", 20))) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(mapper.readTree(r.getBody()).get("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(row(conv, a.user().id())).containsExactly(10, 10);
    }

    /** Membership is checked before seq: an outsider with an absurd seq gets the unknown-conversation 404. */
    @Test
    void nonMemberLeftParticipantAndUnknownConversationGetIdentical404() throws Exception {
        AuthResponse a = register("cnA_");
        AuthResponse b = register("cnB_");
        AuthResponse outsider = register("cnX_");
        UUID conv = dmWithLastSeq100(a, b);
        jdbcClient.sql("UPDATE conversation_participants SET left_at = now() WHERE conversation_id = :c AND user_id = :u")
                .param("c", conv).param("u", b.user().id()).update();

        List<ResponseEntity<String>> responses = List.of(
                cursor(outsider, conv, "read", 1_000_000_000_000L),
                cursor(outsider, conv, "read", -5),
                cursor(b, conv, "read", 1),                               // left the conversation
                cursor(a, UUID.randomUUID(), "read", 1_000_000_000_000L)); // unknown conversation
        for (ResponseEntity<String> r : responses) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            JsonNode n = mapper.readTree(r.getBody());
            assertThat(n.get("code").asText()).isEqualTo("CONVERSATION_NOT_FOUND");
            assertThat(n.get("message").asText())
                    .isEqualTo(mapper.readTree(responses.get(3).getBody()).get("message").asText());
        }
    }
}
