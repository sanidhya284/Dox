package com.chatplatform.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** A metrics password shorter than 16 characters disables scraping entirely (fail closed; ADR-0009). */
@TestPropertySource(properties = "metrics.password=too-short")
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability(tracing = false)
public class MetricsFailClosedTest extends AbstractIntegrationTest {

    @Test
    void shortPasswordMeansNoMetricsUserAtAll() {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth("metrics", "too-short");
        assertThat(restTemplate.exchange("/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(h), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
