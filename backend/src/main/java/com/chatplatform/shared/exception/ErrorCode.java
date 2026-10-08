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
    NOT_A_PARTICIPANT(HttpStatus.FORBIDDEN, "You are not a participant in this conversation"),

    // 404
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Conversation does not exist or is inaccessible"),

    // 409
    USERNAME_TAKEN(HttpStatus.CONFLICT, "Username is already taken"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email is already registered");

    public final HttpStatus status;
    public final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
