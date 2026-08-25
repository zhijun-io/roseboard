package com.roseboard.infrastructure.message;

public final class MessageContractException extends RuntimeException {
    private final MessageErrorCode errorCode;

    public MessageContractException(MessageErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MessageErrorCode errorCode() {
        return errorCode;
    }
}
