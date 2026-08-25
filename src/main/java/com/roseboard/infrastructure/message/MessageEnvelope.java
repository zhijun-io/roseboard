package com.roseboard.infrastructure.message;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MessageEnvelope {
    private final UUID messageId;
    private final String key;
    private final Map<String, byte[]> headers;
    private final Map<String, String> metadata;
    private final MessageDescriptor descriptor;
    private final long createdAt;
    private final byte[] payload;

    private MessageEnvelope(Builder builder) {
        this.messageId = builder.messageId;
        this.key = builder.key;
        this.headers = freezeHeaders(builder.headers);
        this.metadata = freezeMetadata(builder.metadata);
        this.descriptor = Objects.requireNonNull(builder.descriptor, "descriptor");
        this.createdAt = builder.createdAt;
        this.payload = builder.payload == null ? new byte[0] : builder.payload.clone();
        validate();
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID messageId() {
        return messageId;
    }

    public String key() {
        return key;
    }

    public Map<String, byte[]> headers() {
        return headers;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public MessageDescriptor descriptor() {
        return descriptor;
    }

    public long createdAt() {
        return createdAt;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public Builder toBuilder() {
        return builder()
                .messageId(messageId)
                .key(key)
                .headers(copyHeaders(headers))
                .metadata(new LinkedHashMap<>(metadata))
                .descriptor(descriptor)
                .createdAt(createdAt)
                .payload(payload.clone());
    }

    private void validate() {
        if (messageId == null) {
            throw new MessageContractException(MessageErrorCode.INVALID_ENVELOPE, "messageId is required");
        }
        if (key == null || key.isBlank() || key.length() > MessageLimits.MAX_KEY_CHARS || hasControl(key)) {
            throw new MessageContractException(MessageErrorCode.INVALID_ENVELOPE, "Invalid key");
        }
        if (createdAt < 0) {
            throw new MessageContractException(MessageErrorCode.INVALID_ENVELOPE, "createdAt must be >= 0");
        }
        if (payload.length > descriptor.maxPayloadBytes() || payload.length > MessageLimits.MAX_PAYLOAD_BYTES) {
            throw new MessageContractException(MessageErrorCode.PAYLOAD_TOO_LARGE, "payload exceeds limit");
        }
    }

    private static Map<String, byte[]> freezeHeaders(Map<String, byte[]> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (source.size() > MessageLimits.MAX_HEADER_ENTRIES) {
            throw new MessageContractException(MessageErrorCode.HEADERS_TOO_LARGE, "Too many headers");
        }
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : source.entrySet()) {
            String name = entry.getKey();
            byte[] value = entry.getValue() == null ? new byte[0] : entry.getValue();
            if (name == null || name.isBlank() || name.length() > MessageLimits.MAX_HEADER_NAME_CHARS || hasControl(name)) {
                throw new MessageContractException(MessageErrorCode.INVALID_ENVELOPE, "Invalid header name");
            }
            if (value.length > MessageLimits.MAX_HEADER_VALUE_BYTES) {
                throw new MessageContractException(MessageErrorCode.HEADERS_TOO_LARGE, "Header value too large");
            }
            copy.put(name, value.clone());
        }
        return Map.copyOf(copy);
    }

    private static Map<String, String> freezeMetadata(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (source.size() > MessageLimits.MAX_METADATA_ENTRIES) {
            throw new MessageContractException(MessageErrorCode.METADATA_TOO_LARGE, "Too many metadata entries");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (name == null || name.isBlank() || name.length() > MessageLimits.MAX_METADATA_KEY_CHARS || hasControl(name)) {
                throw new MessageContractException(MessageErrorCode.INVALID_ENVELOPE, "Invalid metadata key");
            }
            if (value.length() > MessageLimits.MAX_METADATA_VALUE_CHARS || hasControl(value)) {
                throw new MessageContractException(MessageErrorCode.METADATA_TOO_LARGE, "Invalid metadata value");
            }
            copy.put(name, value);
        }
        return Map.copyOf(copy);
    }

    private static Map<String, byte[]> copyHeaders(Map<String, byte[]> headers) {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        headers.forEach((name, value) -> copy.put(name, value.clone()));
        return copy;
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    public static final class Builder {
        private UUID messageId;
        private String key;
        private Map<String, byte[]> headers = Map.of();
        private Map<String, String> metadata = Map.of();
        private MessageDescriptor descriptor;
        private long createdAt;
        private byte[] payload = new byte[0];

        public Builder messageId(UUID messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder headers(Map<String, byte[]> headers) {
            this.headers = headers;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder descriptor(MessageDescriptor descriptor) {
            this.descriptor = descriptor;
            return this;
        }

        public Builder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder payload(byte[] payload) {
            this.payload = payload;
            return this;
        }

        public MessageEnvelope build() {
            return new MessageEnvelope(this);
        }
    }
}
