package com.chatplatform.auth.dto;

import com.chatplatform.user.dto.UserResponse;

/** Register response body. The refresh token is never in a body: it's set as an HttpOnly cookie (ADR-0010). */
public record AuthResponse(
        UserResponse user,
        String accessToken
) {}
