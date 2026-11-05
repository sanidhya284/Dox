package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.message.dto.SendMessageRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3: forward history paging (?after=) for catching up, and lastSeq in conversation responses. */
public class HistoryForwardPagingTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager txManager;

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

    /** A DM with messages seq 1..n. */
    private UUID dmWith(AuthResponse a, AuthResponse b, int n) {
        UUID conv = restTemplate.postForEntity("/api/conversations/direct",
                new HttpEntity<>(new CreateDirectRequest(b.user().id()), bearer(a)), ConversationResponse.class)
                .getBody().id();
        for (int i = 1; i <= n; i++) {
            ResponseEntity<String> r = restTemplate.postForEntity("/api/conversations/" + conv + "/messages",
                    new HttpEntity<>(new SendMessageRequest(UUID.randomUUID(), "m" + i), bearer(a)), String.class);
            assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        }
        return conv;
    }

    private ResponseEntity<String> get(AuthResponse u, String url) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(bearer(u)), String.class);
    }

    private JsonNode page(AuthResponse u, UUID conv, String query) throws Exception {
        ResponseEntity<String> r = get(u, "/api/conversations/" + conv + "/messages?" + query);
        assertThat(r.getStatusCode()).as(query).isEqualTo(HttpStatus.OK);
        return mapper.readTree(r.getBody());
    }

    private static List<Long> seqs(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("messages").forEach(m -> out.add(m.get("seq").asLong()));
        return out;
    }

    @Test
    void forwardPagesAreAscendingWithAnExactHasMore() throws Exception {
        AuthResponse a = register("fpA_");
        AuthResponse b = register("fpB_");
        UUID conv = dmWith(a, b, 5);

        JsonNode p1 = page(a, conv, "after=0&limit=2");
        assertThat(seqs(p1)).containsExactly(1L, 2L);
        assertThat(p1.get("hasMore").asBoolean()).isTrue();
        assertThat(p1.get("nextCursor").asLong()).isEqualTo(2);

        JsonNode p2 = page(a, conv, "after=2&limit=2");
        assertThat(seqs(p2)).as("strictly after 2").containsExactly(3L, 4L);
        assertThat(p2.get("hasMore").asBoolean()).isTrue();

        JsonNode p3 = page(a, conv, "after=3&limit=2");
        assertThat(seqs(p3)).as("exactly limit left").containsExactly(4L, 5L);
        assertThat(p3.get("hasMore").asBoolean()).as("no more after the last two").isFalse();
        assertThat(p3.get("nextCursor").isNull()).isTrue();

        JsonNode p4 = page(a, conv, "after=5&limit=10");
        assertThat(seqs(p4)).isEmpty();
        assertThat(p4.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void invalidCombinationsAreRejectedAndNonMembersGetTheUsual404() throws Exception {
        AuthResponse a = register("fvA_");
        AuthResponse b = register("fvB_");
        AuthResponse outsider = register("fvX_");
        UUID conv = dmWith(a, b, 2);

        ResponseEntity<String> both = get(a, "/api/conversations/" + conv + "/messages?after=0&before=5");
        assertThat(both.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(both.getBody()).get("code").asText()).isEqualTo("VALIDATION_ERROR");
        ResponseEntity<String> negative = get(a, "/api/conversations/" + conv + "/messages?after=-1");
        assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> nonMember = get(outsider, "/api/conversations/" + conv + "/messages?after=0");
        ResponseEntity<String> unknown = get(outsider, "/api/conversations/" + UUID.randomUUID() + "/messages?after=0");
        assertThat(nonMember.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mapper.readTree(nonMember.getBody()).get("code").asText())
                .isEqualTo(mapper.readTree(unknown.getBody()).get("code").asText());
    }

    @Test
    void badAfterValuesGetA400OrAnEmptyPage() throws Exception {
        AuthResponse a = register("fbA_");
        AuthResponse b = register("fbB_");
        UUID conv = dmWith(a, b, 2);
        String base = "/api/conversations/" + conv + "/messages?after=";

        ResponseEntity<String> negative = get(a, base + "-1");
        assertThat(negative.getStatusCode()).as("negative").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(negative.getBody()).get("code").asText()).isEqualTo("VALIDATION_ERROR");

        ResponseEntity<String> nonNumeric = get(a, base + "abc");
        assertThat(nonNumeric.getStatusCode()).as("non-numeric").isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> overflow = get(a, base + "99999999999999999999");
        assertThat(overflow.getStatusCode()).as("does not fit in a long").isEqualTo(HttpStatus.BAD_REQUEST);
        // Both conversion failures go through GlobalExceptionHandler.handleTypeMismatch.
        assertThat(mapper.readTree(nonNumeric.getBody()).get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(mapper.readTree(overflow.getBody()).get("code").asText()).isEqualTo("VALIDATION_ERROR");

        JsonNode huge = page(a, conv, "after=" + Long.MAX_VALUE);
        assertThat(seqs(huge)).as("valid but beyond every seq: empty page").isEmpty();
        assertThat(huge.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void afterLimitIsClampedTo1And100LikeBefore() throws Exception {
        AuthResponse a = register("fcA_");
        AuthResponse b = register("fcB_");
        UUID conv = dmWith(a, b, 105);

        JsonNode zero = page(a, conv, "after=0&limit=0");
        assertThat(seqs(zero)).as("limit 0 -> 1").containsExactly(1L);
        assertThat(zero.get("hasMore").asBoolean()).isTrue();

        JsonNode big = page(a, conv, "after=0&limit=100000");
        assertThat(seqs(big)).as("limit 100000 -> 100").hasSize(100).startsWith(1L).endsWith(100L);
        assertThat(big.get("hasMore").asBoolean()).isTrue();

        JsonNode backward = page(a, conv, "limit=100000");
        assertThat(seqs(backward)).as("same clamp as before-paging").hasSize(100);
    }

    @Test
    void conversationResponsesCarryLastSeq() throws Exception {
        AuthResponse a = register("flA_");
        AuthResponse b = register("flB_");
        UUID conv = dmWith(a, b, 3);

        JsonNode single = mapper.readTree(get(b, "/api/conversations/" + conv).getBody());
        assertThat(single.get("lastSeq").asLong()).isEqualTo(3);
        JsonNode list = mapper.readTree(get(b, "/api/conversations").getBody());
        JsonNode mine = null;
        for (JsonNode c : list) {
            if (c.get("id").asText().equals(conv.toString())) {
                mine = c;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("lastSeq").asLong()).isEqualTo(3);
    }

    /** Anti-entropy polls this: one row per conversation I am active in, with its lastSeq. */
    @Test
    void headsListLastSeqOfMyActiveConversationsOnly() throws Exception {
        AuthResponse a = register("fhA_");
        AuthResponse b = register("fhB_");
        AuthResponse c = register("fhC_");
        UUID ab = dmWith(a, b, 4);
        UUID ac = dmWith(a, c, 2);
        UUID bc = dmWith(b, c, 1); // a is not a member
        jdbcClient.sql("UPDATE conversation_participants SET left_at = now() WHERE conversation_id = :c AND user_id = :u")
                .param("c", ac).param("u", a.user().id()).update(); // a left this one

        ResponseEntity<String> r = get(a, "/api/conversations/heads");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        java.util.Map<String, Long> heads = new java.util.HashMap<>();
        for (JsonNode h : mapper.readTree(r.getBody())) {
            heads.put(h.get("conversationId").asText(), h.get("lastSeq").asLong());
        }
        assertThat(heads).containsEntry(ab.toString(), 4L);
        assertThat(heads).doesNotContainKey(ac.toString()).doesNotContainKey(bc.toString());
    }

    /**
     * The ascending catch-up query is served by an index in seq order: the plan has an index scan
     * and no Sort node. Sequential and bitmap scans are disabled in this one transaction because on a
     * small, shared test table the planner may prefer them; the assertion is "an index can deliver this order",
     * not a performance claim.
     */
    @Test
    void ascendingCatchUpQueryIsServedByAnIndexWithoutSorting() {
        AuthResponse a = register("fiA_");
        AuthResponse b = register("fiB_");
        UUID conv = dmWith(a, b, 1);

        List<String> plan = new TransactionTemplate(txManager).execute(st -> {
            jdbcClient.sql("SET LOCAL enable_seqscan = off").update();
            // Bitmap scans return rows in physical order, so with one the plan needs a Sort; whether
            // the planner picks one depends on the shared table's stats (it did once, Phase 5 step 2).
            // Disabled too, the remaining index path either delivers seq order or needs a Sort.
            jdbcClient.sql("SET LOCAL enable_bitmapscan = off").update();
            return jdbcClient.sql("EXPLAIN SELECT * FROM messages WHERE conversation_id = :c AND seq > :after "
                            + "ORDER BY seq ASC LIMIT 51")
                    .param("c", conv).param("after", 0L).query(String.class).list();
        });
        String text = String.join("\n", plan);
        assertThat(text).as(text).containsIgnoringCase("Index");
        assertThat(text).as(text).doesNotContain("Sort");
    }
}
