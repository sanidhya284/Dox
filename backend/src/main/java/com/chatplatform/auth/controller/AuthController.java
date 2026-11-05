package com.chatplatform.auth.controller;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.LoginRequest;
import com.chatplatform.auth.dto.RefreshRequest;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.auth.dto.TokenPair;
import com.chatplatform.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final com.chatplatform.shared.ratelimit.RateLimits rateLimits;

    public AuthController(AuthService authService, com.chatplatform.shared.ratelimit.RateLimits rateLimits) {
        this.authService = authService;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
                                 jakarta.servlet.http.HttpServletRequest http) {
        rateLimits.checkRegister(http.getRemoteAddr()); // before the invite check and BCrypt
        return authService.register(request);
    }

    @PostMapping("/login")
    public TokenPair login(@Valid @RequestBody LoginRequest request, jakarta.servlet.http.HttpServletRequest http) {
        rateLimits.checkLogin(http.getRemoteAddr(), request.username()); // before BCrypt
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        // We use RefreshRequest here because it contains the refreshToken we need to revoke.
        authService.logout(request.refreshToken());
    }
}
