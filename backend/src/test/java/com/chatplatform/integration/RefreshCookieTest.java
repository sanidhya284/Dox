package com.chatplatform.integration;

import com.chatplatform.auth.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-0010: the refresh token lives only in an HttpOnly, Secure, SameSite=Strict cookie scoped to /api/auth. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RefreshCookieTest extends AbstractIntegrationTest {

    private static final long SEVEN_DAYS = 7L * 24 * 60 * 60;

    @Autowired
    private JdbcClient jdbc;

    private ResponseEntity<String> register() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        return restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("ck_" + s, "ck_" + s + "@example.com", "password123", "CK " + s), String.class);
    }

    private long activeTokens(String userJsonBody) {
        String id = userJsonBody.replaceAll("(?s).*\"id\":\"([0-9a-f-]{36})\".*", "$1");
        return jdbc.sql("SELECT count(*) FROM refresh_tokens WHERE user_id = CAST(:id AS uuid) AND revoked_at IS NULL")
                .param("id", id).query(Long.class).single();
    }

    private static void assertRefreshCookie(String setCookie) {
        assertThat(setCookie).as("Set-Cookie for " + AuthCookies.NAME).isNotNull();
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api/auth").contains("Max-Age=" + SEVEN_DAYS)
                // host-only: no Domain attribute, so the cookie is never sent to other *.work.gd hosts (ADR-0010)
                .doesNotContainIgnoringCase("Domain=");
    }

    @Test
    void registerAndLoginSetTheCookieAndNeverReturnTheTokenInTheBody() {
        ResponseEntity<String> reg = register();
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertRefreshCookie(AuthCookies.setCookie(reg));
        assertThat(reg.getBody()).contains("accessToken").doesNotContain("refreshToken");

        String username = reg.getBody().replaceAll("(?s).*\"username\":\"([^\"]+)\".*", "$1");
        ResponseEntity<String> login = restTemplate.postForEntity("/api/auth/login",
                Map.of("username", username, "password", "password123"), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertRefreshCookie(AuthCookies.setCookie(login));
        assertThat(login.getBody()).contains("accessToken").doesNotContain("refreshToken");
    }

    @Test
    void refreshWithTheCookieRotatesIt() {
        ResponseEntity<String> reg = register();
        String first = AuthCookies.refreshToken(reg);
        ResponseEntity<String> rotated = AuthCookies.refresh(restTemplate, first);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rotated.getBody()).contains("accessToken").doesNotContain("refreshToken");
        assertRefreshCookie(AuthCookies.setCookie(rotated));
        assertThat(AuthCookies.refreshToken(rotated)).isNotEqualTo(first);
    }

    @Test
    void refreshAndLogoutWithoutAnAllowedOriginAreRefusedAndChangeNothing() {
        ResponseEntity<String> reg = register();
        String token = AuthCookies.refreshToken(reg);
        long before = activeTokens(reg.getBody());

        for (String path : new String[]{"/api/auth/refresh", "/api/auth/logout"}) {
            ResponseEntity<String> noOrigin = restTemplate.postForEntity(path, AuthCookies.request(token, null), String.class);
            assertThat(noOrigin.getStatusCode()).as(path + " without Origin").isEqualTo(HttpStatus.FORBIDDEN);
            ResponseEntity<String> foreign = restTemplate.postForEntity(path,
                    AuthCookies.request(token, "https://evil.example"), String.class);
            assertThat(foreign.getStatusCode()).as(path + " from a foreign origin").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(AuthCookies.setCookie(foreign)).as("no cookie change").isNull();
        }
        assertThat(activeTokens(reg.getBody())).as("nothing revoked or rotated").isEqualTo(before);
        assertThat(AuthCookies.refresh(restTemplate, token).getStatusCode()).as("the token still works")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void logoutRevokesTheTokenAndClearsTheCookie() {
        ResponseEntity<String> reg = register();
        String token = AuthCookies.refreshToken(reg);
        ResponseEntity<String> logout = AuthCookies.logout(restTemplate, token);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(AuthCookies.setCookie(logout)).contains("Max-Age=0").contains("Path=/api/auth");
        assertThat(AuthCookies.refresh(restTemplate, token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshWithoutACookieIs401() {
        assertThat(AuthCookies.refresh(restTemplate, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
