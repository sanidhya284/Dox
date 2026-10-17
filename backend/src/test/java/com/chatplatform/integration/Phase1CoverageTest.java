package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.LoginRequest;
import com.chatplatform.auth.dto.RefreshRequest;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.auth.dto.TokenPair;
import com.chatplatform.conversation.dto.ConversationResponse;
import com.chatplatform.conversation.dto.CreateDirectRequest;
import com.chatplatform.message.dto.MessagePage;
import com.chatplatform.message.dto.MessageResponse;
import com.chatplatform.message.dto.SendMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class Phase1CoverageTest extends AbstractIntegrationTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private AuthResponse register(String suffix) {
        RegisterRequest req = new RegisterRequest(
                "user_" + suffix,
                "user_" + suffix + "@example.com",
                "password123",
                "User " + suffix
        );
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register", req, AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private UUID createDm(String tokenA, UUID userBId) {
        CreateDirectRequest req = new CreateDirectRequest(userBId);
        ResponseEntity<ConversationResponse> res = restTemplate.postForEntity(
                "/api/conversations/direct",
                new HttpEntity<>(req, bearer(tokenA)),
                ConversationResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNotNull();
        return res.getBody().id();
    }

    private MessageResponse sendMessage(String token, UUID conversationId, UUID clientMsgId, String body) {
        SendMessageRequest req = new SendMessageRequest(clientMsgId, body);
        ResponseEntity<MessageResponse> res = restTemplate.postForEntity(
                "/api/conversations/" + conversationId + "/messages",
                new HttpEntity<>(req, bearer(token)),
                MessageResponse.class
        );
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    // ── AUTH ─────────────────────────────────────────────────────────────────

    @Test
    void loginWithCorrectPasswordReturnsWorkingTokens() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse registered = register("login_ok_" + s);

        LoginRequest loginReq = new LoginRequest("user_login_ok_" + s, "password123");
        ResponseEntity<TokenPair> loginRes = restTemplate.postForEntity(
                "/api/auth/login",
                loginReq,
                TokenPair.class
        );
        assertThat(loginRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        TokenPair tokens = loginRes.getBody();
        assertThat(tokens).isNotNull();
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();

        // use the returned access token on GET /api/conversations (200)
        ResponseEntity<List> convRes = restTemplate.exchange(
                "/api/conversations",
                HttpMethod.GET,
                new HttpEntity<>(bearer(tokens.accessToken())),
                List.class
        );
        assertThat(convRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // and the returned refresh token on /api/auth/refresh (200)
        ResponseEntity<TokenPair> refreshRes = restTemplate.postForEntity(
                "/api/auth/refresh",
                new RefreshRequest(tokens.refreshToken()),
                TokenPair.class
        );
        assertThat(refreshRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshRes.getBody()).isNotNull();
        assertThat(refreshRes.getBody().accessToken()).isNotBlank();
    }

    @Test
    void loginIsCaseInsensitiveOnUsername() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String usernameMixed = "MixedCase_" + s;
        RegisterRequest regReq = new RegisterRequest(
                usernameMixed,
                "mixed_" + s + "@example.com",
                "password123",
                "Mixed User"
        );
        ResponseEntity<AuthResponse> regRes = restTemplate.postForEntity("/api/auth/register", regReq, AuthResponse.class);
        assertThat(regRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // login with it lowercased, expect 200
        LoginRequest loginReq = new LoginRequest(usernameMixed.toLowerCase(), "password123");
        ResponseEntity<TokenPair> loginRes = restTemplate.postForEntity(
                "/api/auth/login",
                loginReq,
                TokenPair.class
        );
        assertThat(loginRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginRes.getBody()).isNotNull();
        assertThat(loginRes.getBody().accessToken()).isNotBlank();
    }

    @Test
    void wrongPasswordAndUnknownUserGiveIdenticalResponses() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        register("auth_diff_" + s);

        // Wrong password for existing user
        LoginRequest wrongPassReq = new LoginRequest("user_auth_diff_" + s, "wrong_password");
        ResponseEntity<Map> wrongPassRes = restTemplate.postForEntity(
                "/api/auth/login",
                wrongPassReq,
                Map.class
        );
        assertThat(wrongPassRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Unknown user
        LoginRequest unknownUserReq = new LoginRequest("unknown_user_" + s, "any_password");
        ResponseEntity<Map> unknownUserRes = restTemplate.postForEntity(
                "/api/auth/login",
                unknownUserReq,
                Map.class
        );
        assertThat(unknownUserRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Both return 401, and the JSON "code" field is the same in both
        assertThat(wrongPassRes.getBody()).isNotNull();
        assertThat(unknownUserRes.getBody()).isNotNull();
        assertThat(wrongPassRes.getBody().get("code")).isEqualTo(unknownUserRes.getBody().get("code"));
        assertThat(wrongPassRes.getBody().get("code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void duplicateUsernameReturns409() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String username = "dup_user_" + s;
        String email = "dup_" + s + "@example.com";

        RegisterRequest req1 = new RegisterRequest(username, email, "password123", "User 1");
        ResponseEntity<AuthResponse> res1 = restTemplate.postForEntity("/api/auth/register", req1, AuthResponse.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Exact same username, different email
        RegisterRequest reqExact = new RegisterRequest(username, "other_" + s + "@example.com", "password123", "User 2");
        ResponseEntity<Map> resExact = restTemplate.postForEntity("/api/auth/register", reqExact, Map.class);
        assertThat(resExact.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resExact.getBody().get("code")).isEqualTo("USERNAME_TAKEN");

        // Same username differing only in case
        RegisterRequest reqCase = new RegisterRequest(username.toUpperCase(), "other2_" + s + "@example.com", "password123", "User 3");
        ResponseEntity<Map> resCase = restTemplate.postForEntity("/api/auth/register", reqCase, Map.class);
        assertThat(resCase.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resCase.getBody().get("code")).isEqualTo("USERNAME_TAKEN");

        // Duplicate email with different username
        RegisterRequest reqDupEmail = new RegisterRequest("different_user_" + s, email, "password123", "User 4");
        ResponseEntity<Map> resDupEmail = restTemplate.postForEntity("/api/auth/register", reqDupEmail, Map.class);
        assertThat(resDupEmail.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resDupEmail.getBody().get("code")).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void shortPasswordReturns400() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        RegisterRequest req = new RegisterRequest(
                "short_pwd_" + s,
                "short_" + s + "@example.com",
                "short", // 5 chars, min is 8
                "Short Pwd"
        );
        ResponseEntity<Map> res = restTemplate.postForEntity("/api/auth/register", req, Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void logoutRevokesRefreshToken() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse auth = register("logout_" + s);

        // POST /api/auth/logout with the refresh token (expect 204)
        ResponseEntity<Void> logoutRes = restTemplate.postForEntity(
                "/api/auth/logout",
                new RefreshRequest(auth.refreshToken()),
                Void.class
        );
        assertThat(logoutRes.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // then POST /api/auth/refresh with that same token returns 401
        ResponseEntity<Map> refreshRes = restTemplate.postForEntity(
                "/api/auth/refresh",
                new RefreshRequest(auth.refreshToken()),
                Map.class
        );
        assertThat(refreshRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── CONVERSATIONS ────────────────────────────────────────────────────────

    @Test
    void memberCanGetAndListConversation() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse a = register("mem_a_" + s);
        AuthResponse b = register("mem_b_" + s);
        AuthResponse outsider = register("mem_out_" + s);

        // A creates a DM with B
        CreateDirectRequest dmReq = new CreateDirectRequest(b.user().id());
        ResponseEntity<ConversationResponse> dmRes = restTemplate.postForEntity(
                "/api/conversations/direct",
                new HttpEntity<>(dmReq, bearer(a.accessToken())),
                ConversationResponse.class
        );
        assertThat(dmRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID conversationId = dmRes.getBody().id();

        // A and B can both GET /api/conversations/{id} (200, correct id)
        ResponseEntity<ConversationResponse> getA = restTemplate.exchange(
                "/api/conversations/" + conversationId,
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                ConversationResponse.class
        );
        assertThat(getA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getA.getBody().id()).isEqualTo(conversationId);

        ResponseEntity<ConversationResponse> getB = restTemplate.exchange(
                "/api/conversations/" + conversationId,
                HttpMethod.GET,
                new HttpEntity<>(bearer(b.accessToken())),
                ConversationResponse.class
        );
        assertThat(getB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getB.getBody().id()).isEqualTo(conversationId);

        // An outsider's GET /api/conversations does NOT contain it; A's and B's do.
        ResponseEntity<ConversationResponse[]> listOut = restTemplate.exchange(
                "/api/conversations",
                HttpMethod.GET,
                new HttpEntity<>(bearer(outsider.accessToken())),
                ConversationResponse[].class
        );
        assertThat(listOut.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listOut.getBody()).noneMatch(c -> c.id().equals(conversationId));

        ResponseEntity<ConversationResponse[]> listA = restTemplate.exchange(
                "/api/conversations",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                ConversationResponse[].class
        );
        assertThat(listA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listA.getBody()).anyMatch(c -> c.id().equals(conversationId));

        ResponseEntity<ConversationResponse[]> listB = restTemplate.exchange(
                "/api/conversations",
                HttpMethod.GET,
                new HttpEntity<>(bearer(b.accessToken())),
                ConversationResponse[].class
        );
        assertThat(listB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listB.getBody()).anyMatch(c -> c.id().equals(conversationId));
    }

    @Test
    void listIsOrderedByMostRecentMessage() throws Exception {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse a = register("ord_a_" + s);
        AuthResponse b = register("ord_b_" + s);
        AuthResponse c = register("ord_c_" + s);

        // A creates DM with B and DM with C
        CreateDirectRequest dm1Req = new CreateDirectRequest(b.user().id());
        ResponseEntity<ConversationResponse> dm1Res = restTemplate.postForEntity(
                "/api/conversations/direct",
                new HttpEntity<>(dm1Req, bearer(a.accessToken())),
                ConversationResponse.class
        );
        UUID dm1 = dm1Res.getBody().id();

        Thread.sleep(50);

        CreateDirectRequest dm2Req = new CreateDirectRequest(c.user().id());
        ResponseEntity<ConversationResponse> dm2Res = restTemplate.postForEntity(
                "/api/conversations/direct",
                new HttpEntity<>(dm2Req, bearer(a.accessToken())),
                ConversationResponse.class
        );
        UUID dm2 = dm2Res.getBody().id();

        Thread.sleep(50);

        // A sends a message in the FIRST one (dm1)
        sendMessage(a.accessToken(), dm1, UUID.randomUUID(), "message in dm1");

        // GET /api/conversations for A lists that conversation first
        ResponseEntity<ConversationResponse[]> listRes1 = restTemplate.exchange(
                "/api/conversations",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                ConversationResponse[].class
        );
        assertThat(listRes1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listRes1.getBody()).isNotEmpty();
        assertThat(listRes1.getBody()[0].id()).isEqualTo(dm1);

        Thread.sleep(50);

        // Then a message in the second one (dm2) flips the order
        sendMessage(a.accessToken(), dm2, UUID.randomUUID(), "message in dm2");

        ResponseEntity<ConversationResponse[]> listRes2 = restTemplate.exchange(
                "/api/conversations",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                ConversationResponse[].class
        );
        assertThat(listRes2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listRes2.getBody()).isNotEmpty();
        assertThat(listRes2.getBody()[0].id()).isEqualTo(dm2);
    }

    @Test
    void reportUnreadCounts() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse a = register("unr_a_" + s);
        AuthResponse b = register("unr_b_" + s);

        CreateDirectRequest dmReq = new CreateDirectRequest(b.user().id());
        ResponseEntity<ConversationResponse> dmRes = restTemplate.postForEntity(
                "/api/conversations/direct",
                new HttpEntity<>(dmReq, bearer(a.accessToken())),
                ConversationResponse.class
        );
        UUID dmId = dmRes.getBody().id();

        // A sends 3 messages in a DM with B
        sendMessage(a.accessToken(), dmId, UUID.randomUUID(), "msg 1");
        sendMessage(a.accessToken(), dmId, UUID.randomUUID(), "msg 2");
        sendMessage(a.accessToken(), dmId, UUID.randomUUID(), "msg 3");

        // Fetch both A's and B's conversation list.
        ResponseEntity<ConversationResponse[]> listA = restTemplate.exchange(
                "/api/conversations",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                ConversationResponse[].class
        );
        ResponseEntity<ConversationResponse[]> listB = restTemplate.exchange(
                "/api/conversations",
                HttpMethod.GET,
                new HttpEntity<>(bearer(b.accessToken())),
                ConversationResponse[].class
        );

        long unreadA = -1;
        for (ConversationResponse cr : listA.getBody()) {
            if (cr.id().equals(dmId)) {
                unreadA = cr.unreadCount();
            }
        }
        long unreadB = -1;
        for (ConversationResponse cr : listB.getBody()) {
            if (cr.id().equals(dmId)) {
                unreadB = cr.unreadCount();
            }
        }

        // Do NOT assert on the unread numbers; just print them with System.out ("UNREAD A=.. B=..").
        System.out.println("UNREAD A=" + unreadA + " B=" + unreadB);
    }

    // ── PAGINATION ───────────────────────────────────────────────────────────

    @Test
    void pagesOfThreeCoverAllTenMessagesWithoutOverlap() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse a = register("pg_a_" + s);
        AuthResponse b = register("pg_b_" + s);

        UUID dmId = createDm(a.accessToken(), b.user().id());
        for (int i = 1; i <= 10; i++) {
            sendMessage(a.accessToken(), dmId, UUID.randomUUID(), "msg " + i);
        }

        // Page 1: limit=3 -> [10, 9, 8]
        ResponseEntity<MessagePage> p1 = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?limit=3",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(p1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(p1.getBody()).isNotNull();
        assertThat(p1.getBody().messages().stream().map(MessageResponse::seq).toList())
                .containsExactly(10L, 9L, 8L);
        assertThat(p1.getBody().hasMore()).isTrue();
        assertThat(p1.getBody().nextCursor()).isEqualTo(8L);

        // Page 2: before=8&limit=3 -> [7, 6, 5]
        ResponseEntity<MessagePage> p2 = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?before=" + p1.getBody().nextCursor() + "&limit=3",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(p2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(p2.getBody()).isNotNull();
        assertThat(p2.getBody().messages().stream().map(MessageResponse::seq).toList())
                .containsExactly(7L, 6L, 5L);
        assertThat(p2.getBody().hasMore()).isTrue();
        assertThat(p2.getBody().nextCursor()).isEqualTo(5L);

        // Page 3: before=5&limit=3 -> [4, 3, 2]
        ResponseEntity<MessagePage> p3 = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?before=" + p2.getBody().nextCursor() + "&limit=3",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(p3.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(p3.getBody()).isNotNull();
        assertThat(p3.getBody().messages().stream().map(MessageResponse::seq).toList())
                .containsExactly(4L, 3L, 2L);
        assertThat(p3.getBody().hasMore()).isTrue();
        assertThat(p3.getBody().nextCursor()).isEqualTo(2L);

        // Page 4: before=2&limit=3 -> [1]
        ResponseEntity<MessagePage> p4 = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?before=" + p3.getBody().nextCursor() + "&limit=3",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(p4.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(p4.getBody()).isNotNull();
        assertThat(p4.getBody().messages().stream().map(MessageResponse::seq).toList())
                .containsExactly(1L);
        assertThat(p4.getBody().hasMore()).isFalse();
        assertThat(p4.getBody().nextCursor()).isNull();
    }

    @Test
    void defaultLimitReturnsAllTenInDescendingOrder() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse a = register("deflim_a_" + s);
        AuthResponse b = register("deflim_b_" + s);

        UUID dmId = createDm(a.accessToken(), b.user().id());
        for (int i = 1; i <= 10; i++) {
            sendMessage(a.accessToken(), dmId, UUID.randomUUID(), "msg " + i);
        }

        // no limit param, seq list is exactly 10..1
        ResponseEntity<MessagePage> res = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().messages().stream().map(MessageResponse::seq).toList())
                .containsExactly(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
    }

    @Test
    void beforeOneReturnsEmptyAndBeforeElevenReturnsAllTen() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse a = register("bef_a_" + s);
        AuthResponse b = register("bef_b_" + s);

        UUID dmId = createDm(a.accessToken(), b.user().id());
        for (int i = 1; i <= 10; i++) {
            sendMessage(a.accessToken(), dmId, UUID.randomUUID(), "msg " + i);
        }

        // before=1 returns empty
        ResponseEntity<MessagePage> res1 = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?before=1",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res1.getBody()).isNotNull();
        assertThat(res1.getBody().messages()).isEmpty();
        assertThat(res1.getBody().hasMore()).isFalse();

        // before=11 returns all 10
        ResponseEntity<MessagePage> res11 = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?before=11",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(res11.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res11.getBody()).isNotNull();
        assertThat(res11.getBody().messages().stream().map(MessageResponse::seq).toList())
                .containsExactly(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
    }

    @Test
    void cursorIsStableWhenNewMessageArrives() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse a = register("cs_a_" + s);
        AuthResponse b = register("cs_b_" + s);

        UUID dmId = createDm(a.accessToken(), b.user().id());
        for (int i = 1; i <= 10; i++) {
            sendMessage(a.accessToken(), dmId, UUID.randomUUID(), "msg " + i);
        }

        // fetch page 1 (limit=3, seqs [10,9,8])
        ResponseEntity<MessagePage> p1 = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?limit=3",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(p1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(p1.getBody()).isNotNull();
        assertThat(p1.getBody().messages().stream().map(MessageResponse::seq).toList())
                .containsExactly(10L, 9L, 8L);
        Long cursor = p1.getBody().nextCursor();
        assertThat(cursor).isEqualTo(8L);

        // send an 11th message
        sendMessage(a.accessToken(), dmId, UUID.randomUUID(), "msg 11");

        // fetch page 2 with the cursor from page 1: still exactly [7,6,5]
        ResponseEntity<MessagePage> p2 = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?before=" + cursor + "&limit=3",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(p2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(p2.getBody()).isNotNull();
        assertThat(p2.getBody().messages().stream().map(MessageResponse::seq).toList())
                .containsExactly(7L, 6L, 5L);
    }

    @Test
    void reportLimitEdgeCases() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse a = register("lim_a_" + s);
        AuthResponse b = register("lim_b_" + s);

        UUID dmId = createDm(a.accessToken(), b.user().id());
        for (int i = 1; i <= 10; i++) {
            sendMessage(a.accessToken(), dmId, UUID.randomUUID(), "msg " + i);
        }

        // request limit=0, limit=-1, limit=100000. Do NOT assert; print status code and number of messages returned for each with System.out ("LIMIT 0 -> status=.. n=..").
        ResponseEntity<MessagePage> res0 = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?limit=0",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        int n0 = res0.getBody() != null && res0.getBody().messages() != null ? res0.getBody().messages().size() : -1;
        System.out.println("LIMIT 0 -> status=" + res0.getStatusCode().value() + " n=" + n0);

        ResponseEntity<MessagePage> resNeg = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?limit=-1",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        int nNeg = resNeg.getBody() != null && resNeg.getBody().messages() != null ? resNeg.getBody().messages().size() : -1;
        System.out.println("LIMIT -1 -> status=" + resNeg.getStatusCode().value() + " n=" + nNeg);

        ResponseEntity<MessagePage> resLarge = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?limit=100000",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        int nLarge = resLarge.getBody() != null && resLarge.getBody().messages() != null ? resLarge.getBody().messages().size() : -1;
        System.out.println("LIMIT 100000 -> status=" + resLarge.getStatusCode().value() + " n=" + nLarge);
    }

    @Test
    void duplicateEmailDifferingOnlyInCaseReturns409() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String emailMixed = "Case_" + s + "@example.com";
        String emailLower = "case_" + s + "@example.com";

        RegisterRequest req1 = new RegisterRequest("user_case1_" + s, emailMixed, "password123", "User Case 1");
        ResponseEntity<AuthResponse> res1 = restTemplate.postForEntity("/api/auth/register", req1, AuthResponse.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        RegisterRequest req2 = new RegisterRequest("user_case2_" + s, emailLower, "password123", "User Case 2");
        ResponseEntity<Map> res2 = restTemplate.postForEntity("/api/auth/register", req2, Map.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res2.getBody()).isNotNull();
        assertThat(res2.getBody().get("code")).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void concurrentRegistrationOfSameUsernameYieldsExactlyOneCreated() throws Exception {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String targetUsername = "race_user_" + s;
        int numThreads = 10;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                RegisterRequest req = new RegisterRequest(
                        targetUsername,
                        "race_" + index + "_" + s + "@example.com",
                        "password123",
                        "Race User " + index
                );
                return restTemplate.postForEntity("/api/auth/register", req, Map.class);
            }));
        }

        startLatch.countDown();

        int count201 = 0;
        int count409 = 0;
        int otherCount = 0;

        for (Future<ResponseEntity<Map>> f : futures) {
            ResponseEntity<Map> res = f.get();
            if (res.getStatusCode() == HttpStatus.CREATED) {
                count201++;
            } else if (res.getStatusCode() == HttpStatus.CONFLICT) {
                count409++;
                assertThat(res.getBody()).isNotNull();
                assertThat(res.getBody().get("code")).isEqualTo("USERNAME_TAKEN");
            } else {
                otherCount++;
            }
        }
        executor.shutdown();

        assertThat(count201).isEqualTo(1);
        assertThat(count409).isEqualTo(9);
        assertThat(otherCount).isEqualTo(0);
    }

    @Test
    void limitIsClampedToOneThroughOneHundred() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse a = register("clamp_a_" + s);
        AuthResponse b = register("clamp_b_" + s);

        UUID dmId = createDm(a.accessToken(), b.user().id());
        for (int i = 1; i <= 105; i++) {
            sendMessage(a.accessToken(), dmId, UUID.randomUUID(), "msg " + i);
        }

        // limit=100000 returns exactly 100 messages, seqs 105 down to 6, hasMore true
        ResponseEntity<MessagePage> resLarge = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?limit=100000",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(resLarge.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resLarge.getBody()).isNotNull();
        assertThat(resLarge.getBody().messages()).hasSize(100);
        assertThat(resLarge.getBody().hasMore()).isTrue();
        List<Long> expectedSeqs = java.util.stream.LongStream.iterate(105, x -> x >= 6, x -> x - 1).boxed().toList();
        assertThat(resLarge.getBody().messages().stream().map(MessageResponse::seq).toList())
                .containsExactlyElementsOf(expectedSeqs);

        // limit=0 returns exactly 1 message (seq 105)
        ResponseEntity<MessagePage> resZero = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?limit=0",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(resZero.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resZero.getBody()).isNotNull();
        assertThat(resZero.getBody().messages()).hasSize(1);
        assertThat(resZero.getBody().messages().get(0).seq()).isEqualTo(105L);

        // limit=-1 returns exactly 1 message
        ResponseEntity<MessagePage> resNeg = restTemplate.exchange(
                "/api/conversations/" + dmId + "/messages?limit=-1",
                HttpMethod.GET,
                new HttpEntity<>(bearer(a.accessToken())),
                MessagePage.class
        );
        assertThat(resNeg.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resNeg.getBody()).isNotNull();
        assertThat(resNeg.getBody().messages()).hasSize(1);
        assertThat(resNeg.getBody().messages().get(0).seq()).isEqualTo(105L);
    }
}
