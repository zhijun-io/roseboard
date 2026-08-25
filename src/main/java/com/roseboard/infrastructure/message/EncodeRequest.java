package com.roseboard.infrastructure.message;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EncodeRequest(
        UUID messageId,
        String key,
        String messageType,
        ContentType contentType,
        String schemaId,
        Integer schemaVersion,
        Map<String, byte[]> headers,
        Map<String, String> metadata,
        long createdAt,
        Object value
) {
    public EncodeRequest {
        headers = headers == null ? Map.of() : headers;
        metadata = metadata == null ? Map.of() : metadata;
        Objects.requireNonNull(value, "value");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID messageId;
        private String key;
        private String messageType;
        private ContentType contentType;
        private String schemaId;
        private Integer schemaVersion;
        private Map<String, byte[]> headers;
        private Map<String, String> metadata;
        private long createdAt;
        private Object value;

        public Builder messageId(UUID messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder messageType(String messageType) {
            this.messageType = messageType;
            return this;
        }

        public Builder contentType(ContentType contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder schemaId(String schemaId) {
            this.schemaId = schemaId;
            return this;
        }

        public Builder schemaVersion(Integer schemaVersion) {
            this.schemaVersion = schemaVersion;
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

        public Builder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder value(Object value) {
            this.value = value;
            return this;
        }

        public EncodeRequest build() {
            return new EncodeRequest(
                    messageId, key, messageType, contentType, schemaId, schemaVersion,
                    headers, metadata, createdAt, value);
        }
    }
}
