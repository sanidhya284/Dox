package com.chatplatform.auth.dto;

public record TokenPair(
        String accessToken,
        String refreshToken
) {}
