package com.roseboard.infrastructure.message;

public final class MessageLimits {
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    public static final int MAX_HEADER_ENTRIES = 64;
    public static final int MAX_HEADER_NAME_CHARS = 128;
    public static final int MAX_HEADER_VALUE_BYTES = 8 * 1024;
    public static final int MAX_METADATA_ENTRIES = 64;
    public static final int MAX_METADATA_KEY_CHARS = 128;
    public static final int MAX_METADATA_VALUE_CHARS = 1024;
    public static final int MAX_KEY_CHARS = 256;
    public static final int MAX_MESSAGE_TYPE_CHARS = 128;
    public static final int MAX_SCHEMA_ID_CHARS = 128;
    public static final int MAX_COLLECTION_SIZE = 256;
    public static final int MAX_JSON_DEPTH = 16;

    private MessageLimits() {
    }
}
