package com.roseboard.infrastructure.message;

public enum ContentType {
    APPLICATION_JSON("application/json"),
    APPLICATION_X_PROTOBUF("application/x-protobuf");

    private final String mediaType;

    ContentType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String mediaType() {
        return mediaType;
    }

    public static ContentType fromMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return APPLICATION_JSON;
        }
        String normalized = mediaType.strip().toLowerCase();
        for (ContentType value : values()) {
            if (value.mediaType.equals(normalized)) {
                return value;
            }
        }
        throw new MessageContractException(MessageErrorCode.UNSUPPORTED_CONTENT_TYPE,
                "Unsupported content type: " + mediaType);
    }
}
