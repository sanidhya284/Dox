package com.chatplatform.auth.dto;

import com.chatplatform.user.dto.UserResponse;

public record AuthResponse(
        UserResponse user,
        String accessToken,
        String refreshToken
) {}
