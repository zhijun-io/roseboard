package com.roseboard.infrastructure.message;

import com.roseboard.common.JacksonUtils;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class JsonMessageCodec {
    private static final ObjectMapper STRICT_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonMessageCodec() {
    }

    static byte[] encode(Object value) {
        try {
            return STRICT_MAPPER.writeValueAsBytes(value);
        } catch (JacksonException exception) {
            throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION,
                    "JSON encode failed: " + exception.getMessage());
        }
    }

    static <T> T decode(byte[] payload, Class<T> type) {
        try {
            JsonNode tree = STRICT_MAPPER.readTree(payload);
            validateTree(tree, 0);
            T value = STRICT_MAPPER.treeToValue(tree, type);
            if (value == null) {
                throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION, "JSON decoded to null");
            }
            return value;
        } catch (MessageContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION,
                    "JSON decode failed: " + exception.getMessage());
        }
    }

    static void validateTree(JsonNode node, int depth) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (depth > MessageLimits.MAX_JSON_DEPTH) {
            throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION, "JSON nesting too deep");
        }
        if (node.isFloatingPointNumber()) {
            double value = node.doubleValue();
            if (!Double.isFinite(value)) {
                throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION, "Non-finite number");
            }
        }
        if (node.isArray()) {
            if (node.size() > MessageLimits.MAX_COLLECTION_SIZE) {
                throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION, "Collection too large");
            }
            for (JsonNode child : node) {
                validateTree(child, depth + 1);
            }
            return;
        }
        if (node.isObject()) {
            if (node.size() > MessageLimits.MAX_COLLECTION_SIZE) {
                throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION, "Object too large");
            }
            for (JsonNode child : node) {
                validateTree(child, depth + 1);
            }
        }
    }

    static ObjectMapper mapper() {
        return STRICT_MAPPER;
    }

    static ObjectMapper canonicalMapper() {
        return JacksonUtils.CANONICAL_JSON_MAPPER;
    }
}
