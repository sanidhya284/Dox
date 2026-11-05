package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5-lite (ADR-0009): metrics are never public. /actuator/prometheus needs HTTP Basic for the
 * dedicated metrics user (password from METRICS_PASSWORD; the integration profile sets a test one).
 * /actuator/health is unchanged: public, status only.
 */
// Spring Boot disables metrics export in tests unless asked: without this, /actuator/prometheus would
// not exist in the test context (the running application is unaffected).
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability(tracing = false)
public class MetricsExposureTest extends AbstractIntegrationTest {

    /** Matches application-integration.yml (test-only value). */
    static final String METRICS_PASSWORD = "integration-metrics-password-123";

    @Autowired
    private ObjectMapper mapper;

    private ResponseEntity<String> get(String url, HttpHeaders headers) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static HttpHeaders basic(String user, String password) {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(user, password);
        return h;
    }

    @Test
    void anonymousScrapeIsRejected() {
        assertThat(get("/actuator/prometheus", new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/actuator/metrics", new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anOrdinaryUsersJwtIsNotEnough() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<AuthResponse> reg = restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest("mx_" + s, "mx_" + s + "@example.com", "password123", "MX " + s), AuthResponse.class);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(reg.getBody().accessToken());

        assertThat(get("/actuator/prometheus", h).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void wrongBasicPasswordIsRejected() {
        assertThat(get("/actuator/prometheus", basic("metrics", "not-the-password-xyz")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/actuator/prometheus", basic("someone-else", METRICS_PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void metricsUserBasicCredentialsDoNotWorkOnTheApi() {
        // The metrics user is an operator credential for /actuator only; the API accepts JWT only.
        assertThat(get("/api/conversations", basic("metrics", METRICS_PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/conversations/heads", basic("metrics", METRICS_PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void metricsUserCanScrapePrometheus() {
        ResponseEntity<String> r = get("/actuator/prometheus", basic("metrics", METRICS_PASSWORD));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("jvm_memory_used_bytes").contains("hikaricp_connections");
    }

    @Test
    void healthIsUnchangedPublicStatusOnly() throws Exception {
        ResponseEntity<String> r = get("/actuator/health", new HttpHeaders());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(r.getBody());
        assertThat(body.get("status").asText()).isEqualTo("UP");
        assertThat(body.has("components")).as("no details").isFalse();
    }
}
