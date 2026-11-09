package com.chatplatform.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /api/version (public): the commit the running image was built from, so anyone can see which release is live
 * after an automatic deploy (ADR-0011). The deploy workflow passes GIT_SHA as a Docker build arg; the image exposes it
 * as APP_GIT_SHA. Local and test builds report "unknown". Nothing secret: the repository is public.
 */
@RestController
public class VersionController {

    private final String commit;

    public VersionController(@Value("${app.git-sha:unknown}") String commit) {
        this.commit = commit == null || commit.isBlank() ? "unknown" : commit.trim();
    }

    @GetMapping("/api/version")
    public Map<String, String> version() {
        return Map.of("commit", commit, "shortCommit", commit.length() > 7 ? commit.substring(0, 7) : commit);
    }
}
