package com.chatplatform.auth.dto;

import com.chatplatform.user.dto.UserResponse;

/** Internal result of registering: the new user and their first token pair. */
public record Registration(UserResponse user, TokenPair tokens) {
}
