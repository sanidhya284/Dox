package com.chatplatform.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * All domain errors the application intentionally raises.
 *
 * Each constant carries an HTTP status and a machine-readable code.
 * Controllers catch ApiException and let GlobalExceptionHandler format
 * the response — no error-formatting logic leaks into domain code.
 */
public enum ErrorCode {

    // 400
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),
    CANNOT_DM_SELF(HttpStatus.BAD_REQUEST, "Cannot start a direct message with yourself"),

    // 401
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username or password"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token is invalid, expired, or revoked"),
    NOT_AUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),


    // 403
    REGISTRATION_CLOSED(HttpStatus.FORBIDDEN, "Registration requires a valid invite code"),
    ORIGIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "Request origin is not allowed"),
    GUEST_NOT_ALLOWED(HttpStatus.FORBIDDEN, "Guest accounts can't do this; sign up for a full account"),

    // 404
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Conversation does not exist or is inaccessible"),

    // 409
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "client_msg_id was already used for a different message"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "Username is already taken"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email is already registered"),

    // 429
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; retry later"),
    DEMO_BUSY(HttpStatus.TOO_MANY_REQUESTS, "The demo is full right now; sign up for a free account instead");

    public final HttpStatus status;
    public final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
