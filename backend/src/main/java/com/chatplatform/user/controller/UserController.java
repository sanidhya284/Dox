package com.chatplatform.user.controller;

import com.chatplatform.shared.ratelimit.RateLimits;
import com.chatplatform.user.dto.UserSearchResult;
import com.chatplatform.user.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Users (ADR-0012): prefix search for starting DMs. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final RateLimits rateLimits;

    public UserController(UserService userService, RateLimits rateLimits) {
        this.userService = userService;
        this.rateLimits = rateLimits;
    }

    /** Block (ADR-0012): idempotent, 204. */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/block")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void block(@AuthenticationPrincipal UUID currentUserId,
                      @org.springframework.web.bind.annotation.PathVariable UUID id) {
        userService.block(currentUserId, id);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}/block")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal UUID currentUserId,
                        @org.springframework.web.bind.annotation.PathVariable UUID id) {
        userService.unblock(currentUserId, id);
    }

    @GetMapping("/blocked")
    public List<UserSearchResult> blocked(@AuthenticationPrincipal UUID currentUserId) {
        return userService.blocked(currentUserId);
    }

    @GetMapping("/search")
    public List<UserSearchResult> search(@AuthenticationPrincipal UUID currentUserId,
                                         @RequestParam(name = "q", required = false) String q) {
        rateLimits.checkSearch(currentUserId);
        return userService.search(currentUserId, q);
    }
}
