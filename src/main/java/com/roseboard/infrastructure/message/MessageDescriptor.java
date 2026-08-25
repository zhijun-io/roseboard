package com.roseboard.infrastructure.message;

import java.util.Objects;
import java.util.regex.Pattern;

public record MessageDescriptor(
        String messageType,
        ContentType contentType,
        String schemaId,
        int schemaVersion,
        int maxPayloadBytes
) {
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    public MessageDescriptor {
        Objects.requireNonNull(contentType, "contentType");
        requireMessageType(messageType);
        requireSchemaId(schemaId);
        requireSchemaVersion(schemaVersion);
        if (maxPayloadBytes < 1 || maxPayloadBytes > MessageLimits.MAX_PAYLOAD_BYTES) {
            throw new MessageContractException(MessageErrorCode.INVALID_DESCRIPTOR,
                    "maxPayloadBytes out of range");
        }
    }

    public static void requireRegistration(String messageType, String schemaId, int schemaVersion) {
        requireMessageType(messageType);
        requireSchemaId(schemaId);
        requireSchemaVersion(schemaVersion);
    }

    public static MessageDescriptor json(String messageType, String schemaId, int schemaVersion) {
        return new MessageDescriptor(
                messageType,
                ContentType.APPLICATION_JSON,
                schemaId,
                schemaVersion,
                MessageLimits.MAX_PAYLOAD_BYTES);
    }

    public static MessageDescriptor protobuf(String messageType, String schemaId, int schemaVersion) {
        return new MessageDescriptor(
                messageType,
                ContentType.APPLICATION_X_PROTOBUF,
                schemaId,
                schemaVersion,
                MessageLimits.MAX_PAYLOAD_BYTES);
    }

    private static void requireMessageType(String messageType) {
        requireName(messageType, "messageType", MessageLimits.MAX_MESSAGE_TYPE_CHARS);
    }

    private static void requireSchemaId(String schemaId) {
        requireName(schemaId, "schemaId", MessageLimits.MAX_SCHEMA_ID_CHARS);
    }

    private static void requireSchemaVersion(int schemaVersion) {
        if (schemaVersion < 1) {
            throw new MessageContractException(MessageErrorCode.INVALID_DESCRIPTOR,
                    "schemaVersion must be a positive integer");
        }
    }

    private static void requireName(String value, String field, int maxChars) {
        if (value == null || value.isBlank() || value.length() > maxChars
                || !NAME_PATTERN.matcher(value).matches()) {
            throw new MessageContractException(MessageErrorCode.INVALID_DESCRIPTOR, "Invalid " + field);
        }
    }
}
