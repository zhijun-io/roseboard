package com.roseboard.infrastructure.websocket;

public enum SubscriptionErrorCode {
    BAD_REQUEST(1, "Bad request"),
    UNAUTHORIZED(2, "Unauthorized"),
    INTERNAL_ERROR(3, "Internal error"),
    TOO_MANY_UPDATES(34, "Too many updates!");

    private final int code;
    private final String defaultMsg;

    SubscriptionErrorCode(int code, String defaultMsg) {
        this.code = code;
        this.defaultMsg = defaultMsg;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMsg() {
        return defaultMsg;
    }
}
