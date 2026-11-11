package com.chatplatform.auth.controller;

import com.chatplatform.auth.dto.AccessTokenResponse;
import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.LoginRequest;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.auth.dto.Registration;
import com.chatplatform.auth.dto.TokenPair;
import com.chatplatform.auth.service.AuthService;
import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import com.chatplatform.shared.ratelimit.RateLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints (ADR-0003, ADR-0010). Access tokens go in response bodies and live in the client's memory;
 * refresh tokens travel only in the HttpOnly {@link RefreshCookie}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RateLimits rateLimits;
    private final RefreshCookie refreshCookie;
    private final AuthOriginGuard originGuard;
    private final com.chatplatform.shared.metrics.ChatMetrics metrics;

    public AuthController(AuthService authService, RateLimits rateLimits, RefreshCookie refreshCookie,
                          AuthOriginGuard originGuard, com.chatplatform.shared.metrics.ChatMetrics metrics) {
        this.metrics = metrics;
        this.authService = authService;
        this.rateLimits = rateLimits;
        this.refreshCookie = refreshCookie;
        this.originGuard = originGuard;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http,
                                 HttpServletResponse response) {
        rateLimits.checkRegister(http.getRemoteAddr()); // before the invite check and BCrypt
        try {
            rateLimits.checkGlobalSignup(); // all IPs together (ADR-0012); fails closed
        } catch (com.chatplatform.shared.exception.RateLimitedException e) {
            metrics.signup(false);
            throw e;
        }
        Registration registration = authService.register(request);
        metrics.signup(true);
        setCookie(response, registration.tokens());
        return new AuthResponse(registration.user(), registration.tokens().accessToken());
    }

    @PostMapping("/login")
    public AccessTokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http,
                                     HttpServletResponse response) {
        rateLimits.checkLogin(http.getRemoteAddr(), request.username()); // before BCrypt
        TokenPair tokens = authService.login(request);
        setCookie(response, tokens);
        return new AccessTokenResponse(tokens.accessToken());
    }

    @PostMapping("/refresh")
    public AccessTokenResponse refresh(@CookieValue(name = RefreshCookie.NAME, required = false) String token,
                                       @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
                                       HttpServletResponse response) {
        originGuard.check(origin); // first: a cross-site request changes nothing
        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
        TokenPair tokens;
        try {
            tokens = authService.refresh(token);
        } catch (ApiException e) {
            // Invalid, expired or replayed (the family is now revoked): the browser should drop the cookie.
            response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString());
            throw e;
        }
        setCookie(response, tokens);
        return new AccessTokenResponse(tokens.accessToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = RefreshCookie.NAME, required = false) String token,
                       @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
                       HttpServletResponse response) {
        originGuard.check(origin);
        if (token != null && !token.isBlank()) {
            authService.logout(token);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString());
    }

    private void setCookie(HttpServletResponse response, TokenPair tokens) {
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.issue(tokens.refreshToken()).toString());
    }
}
