package com.chatplatform.auth.dto;

/** Login and refresh response body; the refresh token goes in the HttpOnly cookie (ADR-0010). */
public record AccessTokenResponse(String accessToken) {
}
