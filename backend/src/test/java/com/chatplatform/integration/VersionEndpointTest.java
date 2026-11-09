package com.chatplatform.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/version is public and reports the commit the running image was built from (ADR-0011 Decision 10:
 * shows which release is live after an automatic deploy). In the image, APP_GIT_SHA comes from the GIT_SHA build arg.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@TestPropertySource(properties = "app.git-sha=0123456789abcdef0123456789abcdef01234567")
class VersionEndpointTest extends AbstractIntegrationTest {

    @Test
    @SuppressWarnings("rawtypes")
    void versionIsPublicAndReportsTheBuildCommit() {
        ResponseEntity<Map> r = restTemplate.getForEntity("/api/version", Map.class);
        assertThat(r.getStatusCode()).as("no token needed").isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("commit", "0123456789abcdef0123456789abcdef01234567")
                .containsEntry("shortCommit", "0123456");
    }
}
