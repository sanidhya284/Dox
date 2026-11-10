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

    @GetMapping("/search")
    public List<UserSearchResult> search(@AuthenticationPrincipal UUID currentUserId,
                                         @RequestParam(name = "q", required = false) String q) {
        rateLimits.checkSearch(currentUserId);
        return userService.search(currentUserId, q);
    }
}
