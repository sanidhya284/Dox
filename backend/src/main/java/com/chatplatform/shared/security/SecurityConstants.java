package com.chatplatform.shared.security;

public final class SecurityConstants {
    private SecurityConstants() {}
    
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String AUTHORIZATION_HEADER = "Authorization";
    
    // 15 minutes in milliseconds
    public static final long ACCESS_TOKEN_EXPIRATION_MS = 15 * 60 * 1000;
    
    // 7 days in milliseconds
    public static final long REFRESH_TOKEN_EXPIRATION_MS = 7L * 24 * 60 * 60 * 1000;
}
