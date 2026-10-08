package com.chatplatform.shared.exception;

/**
 * Runtime exception that carries a typed ErrorCode.
 *
 * Thrown by service layer, caught by GlobalExceptionHandler.
 * Never exposes SQL errors, stack traces, or internal state.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.defaultMessage);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
