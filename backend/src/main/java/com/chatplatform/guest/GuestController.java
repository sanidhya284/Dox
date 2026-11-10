package com.chatplatform.guest;

import com.chatplatform.auth.controller.AuthOriginGuard;
import com.chatplatform.auth.controller.RefreshCookie;
import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.Registration;
import com.chatplatform.shared.ratelimit.RateLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/auth/guest} (ADR-0012): "Try the demo". Origin-checked like the other cookie endpoints
 * (it sets the refresh cookie), limited per IP, and capped in total (DEMO_BUSY).
 */
@RestController
public class GuestController {

    private final GuestService guestService;
    private final RateLimits rateLimits;
    private final RefreshCookie refreshCookie;
    private final AuthOriginGuard originGuard;

    public GuestController(GuestService guestService, RateLimits rateLimits, RefreshCookie refreshCookie,
                           AuthOriginGuard originGuard) {
        this.guestService = guestService;
        this.rateLimits = rateLimits;
        this.refreshCookie = refreshCookie;
        this.originGuard = originGuard;
    }

    @PostMapping("/api/auth/guest")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse create(@RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
                               HttpServletRequest http, HttpServletResponse response) {
        originGuard.check(origin);
        rateLimits.checkGuest(http.getRemoteAddr());
        Registration r = guestService.create();
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.issue(r.tokens().refreshToken()).toString());
        return new AuthResponse(r.user(), r.tokens().accessToken());
    }
}
